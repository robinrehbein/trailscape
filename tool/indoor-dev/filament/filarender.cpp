// filarender — Filament-Referenzrenderer fuer Indoor-Welten (Entwicklerwerkzeug).
//
// Rendert eine Szene mit **derselben Filament-Version wie die App (1.75.1)** und
// denselben Einstellungen wie `app/.../indoor/render/FilamentCore.kt`: Sonne mit
// DPCF-Schatten, Umgebungslicht aus SH-Koeffizienten, Himmels-Cubemap bzw.
// Himmelsmaterial, Dunst, Tonwertabbildung/Farbgebung, Wassermaterial, Cockpit.
// Die Szene (Kacheln, Kamera, Fahrer) beschreibt der Betrachter
// (`viewer/shots.mjs --filament`), Licht und Himmel `indoor-dev sky`.
//
// Ohne GPU laeuft das auf Mesa llvmpipe (OpenGL 4.5) unter Xvfb: gleiche Shader,
// gleiche Abbildung, aber Desktop-GL statt OpenGL ES — Genauigkeit und Leistung
// sagen nichts ueber ein Handy.
//
// Aufruf: filarender <scene.json> <out.png>

#include "json.hpp"  // vor Filament: deren Makro assert_invariant stoert sonst

#include <filament/Camera.h>
#include <filament/ColorGrading.h>
#include <filament/Engine.h>
#include <filament/IndexBuffer.h>
#include <filament/IndirectLight.h>
#include <filament/LightManager.h>
#include <filament/Material.h>
#include <filament/MaterialInstance.h>
#include <filament/RenderableManager.h>
#include <filament/Renderer.h>
#include <filament/Scene.h>
#include <filament/Skybox.h>
#include <filament/SwapChain.h>
#include <filament/Texture.h>
#include <filament/TextureSampler.h>
#include <filament/ToneMapper.h>
#include <filament/TransformManager.h>
#include <filament/VertexBuffer.h>
#include <filament/View.h>
#include <filament/Viewport.h>
#include <gltfio/Animator.h>
#include <gltfio/AssetLoader.h>
#include <gltfio/FilamentAsset.h>
#include <gltfio/FilamentInstance.h>
#include <gltfio/MaterialProvider.h>
#include <gltfio/ResourceLoader.h>
#include <gltfio/TextureProvider.h>
#include <gltfio/materials/uberarchive.h>
#include <utils/EntityManager.h>
#include <utils/NameComponentManager.h>

#include <chrono>
#include <functional>
#include <cmath>
#include <cstring>
#include <unistd.h>
#include <cstdio>
#include <fstream>
#include <map>
#include <set>
#include <string>
#include <thread>
#include <vector>

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"
// Nahdetail-Karten der Belaege (JPEG); eigene, statische Kopie — Filaments libstb bleibt unberuehrt.
#define STB_IMAGE_STATIC
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

using namespace filament;
using namespace filament::math;
using json = nlohmann::json;
using utils::Entity;

static std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) {
        fprintf(stderr, "filarender: kann %s nicht lesen\n", path.c_str());
        exit(2);
    }
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

static json readJson(const std::string& path) {
    auto b = readFile(path);
    return json::parse(b.begin(), b.end());
}

static float3 f3(const json& j) { return { j[0].get<float>(), j[1].get<float>(), j[2].get<float>() }; }
static double3 d3(const json& j) { return { j[0].get<double>(), j[1].get<double>(), j[2].get<double>() }; }

template <typename T>
static T opt(const json& j, const char* key, T def) {
    return j.contains(key) && !j[key].is_null() ? j[key].get<T>() : def;
}

// Knotenart wie RenderSupport.nodeCategory (erster bekannter Name von unten nach oben).
static const std::set<std::string> KNOWN = { "terrain", "route", "roads", "buildings", "trees", "water", "props" };

struct Loaded {
    gltfio::FilamentAsset* asset = nullptr;
    std::map<Entity, std::string> category;  // Renderable → Knotenart (leer = unbekannt)
};

static Loaded loadAsset(Engine* engine, gltfio::AssetLoader* loader, gltfio::ResourceLoader* res, const std::string& path) {
    auto bytes = readFile(path);
    Loaded l;
    l.asset = loader->createAsset(bytes.data(), (uint32_t)bytes.size());
    if (!l.asset) {
        fprintf(stderr, "filarender: gltfio kann %s nicht lesen\n", path.c_str());
        exit(3);
    }
    res->loadResources(l.asset);
    l.asset->releaseSourceData();
    auto& tm = engine->getTransformManager();
    const Entity root = l.asset->getRoot();
    const Entity* rs = l.asset->getRenderableEntities();
    for (size_t i = 0; i < l.asset->getRenderableEntityCount(); i++) {
        Entity cur = rs[i];
        std::string cat;
        for (int guard = 0; guard < 32 && !cur.isNull(); guard++) {
            const char* n = l.asset->getName(cur);
            if (n) {
                std::string s(n);
                if (s.rfind("terrain_sub_", 0) == 0 || KNOWN.count(s)) {
                    cat = s;
                    break;
                }
            }
            if (cur == root) break;
            auto ti = tm.getInstance(cur);
            if (!ti) break;
            cur = tm.getParent(ti);
        }
        l.category[rs[i]] = cat;
    }
    return l;
}

static mat4f translation(float3 p) { return mat4f::translation(p); }

static mat4f matFromJson(const json& j) {
    mat4f m;
    for (int c = 0; c < 4; c++)
        for (int r = 0; r < 4; r++) m[c][r] = j[c * 4 + r].get<float>();
    return m;
}

// Halbfloat → float (wie SkyModel.halfToDouble, ohne Subnormale)
static float halfToFloat(uint16_t h) {
    int e = (h >> 10) & 0x1F;
    if (e == 0) return 0.f;
    int m = h & 0x3FF;
    float s = (h & 0x8000) ? -1.f : 1.f;
    return s * (1.f + m / 1024.f) * std::ldexp(1.f, e - 15);
}

static Material* loadMaterial(Engine* engine, const std::string& path) {
    auto b = readFile(path);
    return Material::Builder().package(b.data(), b.size()).build(*engine);
}

// Wie WorldMaterialProvider/WorldMaterialRules der App: `mat_terrain` → terrain.mat, Laub
// (`x_mat_tree_cards`, `mat_tree_leaves`) → foliage.mat, Belaege und Randstreifen →
// surface.mat, ihre Auslaeufe → surface_blend.mat; alles andere der Ubershader. gltfio setzt
// danach dieselben Parameter (baseColorFactor …) und bindet die Textur an `baseColorMap`.
enum Kind { UBER = 0, TERRAIN = 1, FOLIAGE = 2, SURFACE = 3, SURFACE_BLEND = 4 };

static bool startsWith(const std::string& s, const char* p) { return s.rfind(p, 0) == 0; }
static bool endsWith(const std::string& s, const char* p) {
    size_t n = strlen(p);
    return s.size() >= n && s.compare(s.size() - n, n, p) == 0;
}

// Spiegel von WorldMaterialRules.kindFor (Kotlin, getestet).
static Kind kindFor(const std::string& l, gltfio::AlphaMode a, bool tex, bool unlit) {
    if (!tex || unlit) return UBER;
    using gltfio::AlphaMode;
    if (l == "mat_terrain") return a == AlphaMode::OPAQUE ? TERRAIN : UBER;
    if (l == "x_mat_tree_cards" || l == "mat_tree_leaves") return a == AlphaMode::MASK ? FOLIAGE : UBER;
    if (a == AlphaMode::OPAQUE && (startsWith(l, "mat_route_") || (startsWith(l, "x_mat_road_") && !endsWith(l, "_edge")) ||
            (startsWith(l, "x_mat_route_verge_") && !endsWith(l, "_fade")) || l == "x_mat_route_shoulder" || l == "x_mat_route_sidewalk"))
        return SURFACE;
    if (a == AlphaMode::BLEND && ((startsWith(l, "x_mat_route_") && endsWith(l, "_fade")) || l == "x_mat_route_fringe"))
        return SURFACE_BLEND;
    return UBER;
}

// Spiegel von SurfaceNearDetail.keyFor (Kotlin, getestet): Belag der Fahrbahn aus dem
// Materialnamen (`mat_route_<belag>`, `x_mat_road_<belag>`), sonst leer (Randstreifen usw.).
static std::string nearKey(const std::string& l, const json& kinds) {
    std::string k;
    if (startsWith(l, "mat_route_")) k = l.substr(10);
    else if (startsWith(l, "x_mat_road_")) k = l.substr(11);
    return kinds.contains(k) ? k : std::string();
}

// Spiegel von PbrRules.keyFor (Kotlin, getestet): Schluessel der PBR-Textur aus dem
// Materialnamen — Belag (`mat_route_<b>`, `x_mat_road_<b>`, Auslauf `x_mat_route_<b>_fade`) oder
// Randstreifen (`x_mat_route_verge_grass`, `…_shoulder`, `…_fringe` …); Kanten, Bordsteine leer.
static std::string pbrKey(const std::string& l, const json& kinds) {
    std::string k;
    if (startsWith(l, "mat_route_")) k = l.substr(10);
    else if (startsWith(l, "x_mat_road_")) k = l.substr(11);
    else if (startsWith(l, "x_mat_route_")) k = l.substr(12);
    else return std::string();
    if (endsWith(k, "_edge")) return std::string();
    if (endsWith(k, "_fade")) k = k.substr(0, k.size() - 5);
    return kinds.contains(k) ? k : std::string();
}

// Spiegel von WorldMaterialRules.detailClass: Gewichte Wiese, Erde, Waldboden, Schotter.
static float4 detailClass(const std::string& l) {
    if (l.find("verge_grass") != std::string::npos) return { 1, 0, 0, 0 };
    if (l.find("verge_forest") != std::string::npos) return { 0, 0, 1, 0 };
    if (l.find("verge_shoulder") != std::string::npos || l == "x_mat_route_shoulder") return { 0, 0.4f, 0, 0.6f };
    if (l == "x_mat_route_fringe") return { 0.7f, 0, 0, 0 };
    return { 0, 0, 0, 0 };
}

struct WorldProvider : public gltfio::MaterialProvider {
    gltfio::MaterialProvider* uber;
    Material* mats[5] = { nullptr, nullptr, nullptr, nullptr, nullptr };
    std::function<void(MaterialInstance*, int, const std::string&)> setup;
    mutable std::vector<const Material*> all;
    size_t counts[5] = { 0, 0, 0, 0, 0 };

    explicit WorldProvider(gltfio::MaterialProvider* u) : uber(u) {}

    MaterialInstance* createMaterialInstance(gltfio::MaterialKey* config, gltfio::UvMap* uvmap,
            const char* label, const char* extras) override {
        const std::string l = label ? label : "";
        Kind kind = kindFor(l, config->alphaMode, config->hasBaseColorTexture, config->unlit);
        if (kind == UBER || !mats[kind]) {
            counts[0]++;
            return uber->createMaterialInstance(config, uvmap, label, extras);
        }
        gltfio::constrainMaterial(config, uvmap);
        MaterialInstance* mi = mats[kind]->createInstance(label);
        if (kind == FOLIAGE && !config->doubleSided) mi->setDoubleSided(false);
        counts[kind]++;
        if (setup) setup(mi, kind, l);
        return mi;
    }
    Material* getMaterial(gltfio::MaterialKey* config, gltfio::UvMap* uvmap, const char* label) override {
        return uber->getMaterial(config, uvmap, label);
    }
    const Material* const* getMaterials() const noexcept override {
        all.clear();
        for (size_t i = 0; i < uber->getMaterialsCount(); i++) all.push_back(uber->getMaterials()[i]);
        for (int k = 1; k < 5; k++) if (mats[k]) all.push_back(mats[k]);
        return all.data();
    }
    size_t getMaterialsCount() const noexcept override {
        size_t n = uber->getMaterialsCount();
        for (int k = 1; k < 5; k++) if (mats[k]) n++;
        return n;
    }
    void destroyMaterials() override { uber->destroyMaterials(); }
    bool needsDummyData(VertexAttribute a) const noexcept override { return uber->needsDummyData(a); }
};

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "Aufruf: filarender <scene.json> <out.png>\n");
        return 2;
    }
    const json S = readJson(argv[1]);
    const std::string skyDir = S["sky"].get<std::string>();
    const json K = readJson(skyDir + "/sky.json");
    const json L = K.contains("look") ? K["look"] : json::object();  // neue Stimmung (fehlt = alter Stand)
    const json settings = S.contains("settings") ? S["settings"] : json::object();
    const bool shadows = opt(settings, "shadows", true);
    const bool fogOn = opt(settings, "fog", true);
    const bool skyOn = opt(settings, "sky", true);
    const bool post = opt(settings, "postProcessing", true);
    const bool ao = opt(settings, "ambientOcclusion", false);
    const bool bloom = opt(settings, "bloom", false);
    // Bildentstehung (Branch indoor-renderqualitaet, FilamentCore/RenderQuality.kt): alle
    // Vorgaben = Stand davor, damit Vorher-Bilder gleich bleiben.
    const bool reflections = opt(settings, "reflections", false);   // Spiegelungs-IBL aus reflections.bin
    const bool taa = opt(settings, "taa", false);                   // zeitliche Kantenglaettung statt MSAA
    const int effectQuality = opt(settings, "effectQuality", 0);    // 0 LOW, 1 MEDIUM, 2 HIGH (SSAO, Bloom)
    const bool contactShadows = opt(settings, "contactShadows", false);
    const int cascades = opt(settings, "cascades", 3);
    const bool stableShadows = opt(settings, "stableShadows", false);
    // Java-Vorgabe von ShadowOptions.lispsm ist false (C++: true) — die App setzt es nicht.
    const bool lispsm = opt(settings, "lispsm", false);
    const bool vignette = opt(settings, "vignette", false);
    const bool whiteBalance = opt(settings, "whiteBalance", false);
    const bool terrainShadows = opt(settings, "terrainShadows", false);  // Gelaende wirft Schatten
    const float shadowFarM = opt(settings, "shadowFarM", 300);
    const int W = S["width"], H = S["height"];
    const double timeS = opt(S, "timeS", 0.0);

    auto t0 = std::chrono::steady_clock::now();
    // FILARENDER_BACKEND=vulkan: lavapipe statt llvmpipe-GL (Schattenvergleich, siehe docs/indoor/render.md)
    const char* be = getenv("FILARENDER_BACKEND");
    Engine* engine = Engine::create(be && std::string(be) == "vulkan" ? Engine::Backend::VULKAN : Engine::Backend::OPENGL);
    if (!engine) return 4;
    SwapChain* sc = engine->createSwapChain(W, H, SwapChain::CONFIG_READABLE);
    Renderer* renderer = engine->createRenderer();
    Scene* scene = engine->createScene();
    View* view = engine->createView();
    Entity camE = utils::EntityManager::get().create();
    Camera* cam = engine->createCamera(camE);
    view->setScene(scene);
    view->setCamera(cam);
    view->setViewport({ 0, 0, (uint32_t)W, (uint32_t)H });

    // ---- Kamera (FilamentCore.updateProjection / lookAt) ----
    const json C = S["camera"];
    cam->setProjection(C["fovV"].get<double>(), double(W) / H, opt(C, "near", 0.5), opt(C, "far", 25000.0), Camera::Fov::VERTICAL);
    cam->setShift({ opt(C, "shiftX", 0.0), opt(C, "shiftY", 0.0) });
    cam->lookAt(d3(C["eye"]), d3(C["target"]), C.contains("up") ? d3(C["up"]) : double3{ 0, 1, 0 });
    // Belichtung: Blende 16, ISO 100; Verschlusszeit aus EV100 der Stimmung (Vorgabe 1/125 s = EV 15).
    const double ev100 = opt(L, "ev100", std::log2(16.0 * 16.0 * 125.0));
    cam->setExposure(16.f, float(16.0 * 16.0 / std::pow(2.0, ev100)), 100.f);

    // ---- Sonne ----
    Entity sunE = utils::EntityManager::get().create();
    {
        LightManager::ShadowOptions so;
        so.mapSize = 2048;
        so.shadowCascades = (uint8_t)cascades;
        so.cascadeSplitPositions[0] = 0.05f;
        so.cascadeSplitPositions[1] = 0.2f;
        if (cascades >= 4) {
            // RenderQuality.cascadeSplits: 8 m, 30 m, 100 m (Anteile der Reichweite)
            so.cascadeSplitPositions[0] = 8.f / shadowFarM;
            so.cascadeSplitPositions[1] = 30.f / shadowFarM;
            so.cascadeSplitPositions[2] = 100.f / shadowFarM;
        }
        so.shadowFar = shadowFarM;
        so.shadowFarHint = shadowFarM;
        so.shadowNearHint = 1.f;
        so.stable = stableShadows;
        so.lispsm = lispsm;
        so.screenSpaceContactShadows = contactShadows;
        so.stepCount = 8;
        so.maxShadowDistance = 0.5f;
        const double3 enu = d3(K["sunDirEnu"]);
        const float3 toSun = normalize(float3{ float(enu.x), float(enu.z), float(-enu.y) });
        LightManager::Builder(LightManager::Type::SUN)
            .color(f3(K["sunColor"]))
            .intensity(K["sunLux"].get<float>())
            .direction(-toSun)
            .castShadows(shadows)
            .shadowOptions(so)
            .sunAngularRadius(opt(L, "sunAngularRadius", 1.2f))
            .sunHaloSize(opt(L, "sunHaloSize", 8.f))
            .sunHaloFalloff(opt(L, "sunHaloFalloff", 80.f))
            .build(*engine, sunE);
        scene->addEntity(sunE);
    }

    // ---- Umgebungslicht ----
    float3 sh[9];
    for (int i = 0; i < 9; i++) sh[i] = { K["sh"][i * 3].get<float>(), K["sh"][i * 3 + 1].get<float>(), K["sh"][i * 3 + 2].get<float>() };
    IndirectLight* ibl = IndirectLight::Builder().irradiance(3, sh).intensity(K["ambientLux"].get<float>()).build(*engine);
    // FilamentCore nutzt radiance(3, sh); in C++ heisst das irradiance(bands, sh) mit Strahldichte-SH
    // (Filament multipliziert intern mit A[i], siehe IndirectLight.cpp).
    {
        IndirectLight::Builder ib;
        ib.radiance(3, sh).intensity(K["ambientLux"].get<float>());
        if (reflections && K.contains("reflectionsSize")) {
            // Vorgefilterte Mip-Kette aus SkyReflections (wie FilamentCore.buildReflections)
            const int rs = K["reflectionsSize"], rl = K["reflectionsLevels"];
            auto raw = readFile(skyDir + "/reflections.bin");
            Texture* rt = Texture::Builder().width(rs).height(rs).levels(rl).sampler(Texture::Sampler::SAMPLER_CUBEMAP)
                              .format(Texture::InternalFormat::R11F_G11F_B10F).build(*engine);
            size_t off = 0;
            for (int lv = 0; lv < rl; lv++) {
                const int n = std::max(1, rs >> lv);
                const size_t bytes = size_t(6) * n * n * 3 * 2;
                auto* buf = new uint8_t[bytes];
                memcpy(buf, raw.data() + off, bytes);
                off += bytes;
                rt->setImage(*engine, lv, 0, 0, 0, n, n, 6,
                    Texture::PixelBufferDescriptor(buf, bytes, Texture::Format::RGB, Texture::Type::HALF,
                        [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
            }
            ib.reflections(rt);
        }
        IndirectLight* r = ib.build(*engine);
        engine->destroy(ibl);
        ibl = r;
    }
    scene->setIndirectLight(ibl);

    // ---- Himmel ----
    const int cube = K["cubeSize"];
    Texture* skyTex = nullptr;
    Material* skyMat = nullptr;
    MaterialInstance* skyMi = nullptr;
    Entity skyE;
    const std::string matDir = opt<std::string>(S, "materials", "");
    const bool skyMaterial = skyOn && L.contains("skyMaterial") && !matDir.empty();
    if (skyOn && !skyMaterial) {
        auto raw = readFile(skyDir + "/cube.bin");
        auto* buf = new uint16_t[raw.size() / 2];
        memcpy(buf, raw.data(), raw.size());
        skyTex = Texture::Builder().width(cube).height(cube).levels(1).sampler(Texture::Sampler::SAMPLER_CUBEMAP)
                     .format(Texture::InternalFormat::R11F_G11F_B10F).build(*engine);
        skyTex->setImage(*engine, 0, 0, 0, 0, cube, cube, 6,
            Texture::PixelBufferDescriptor(buf, raw.size(), Texture::Format::RGB, Texture::Type::HALF,
                [](void* b, size_t, void*) { delete[] (uint16_t*)b; }));
        scene->setSkybox(Skybox::Builder().environment(skyTex).showSun(true).build(*engine));
    } else if (!skyOn) {
        const float3 h = f3(K["skyHorizon"]);
        scene->setSkybox(Skybox::Builder().color({ h.x, h.y, h.z, 1.f }).build(*engine));
    }
    // Wolken-/Wellentextur: Rauschen aus SkyClouds.cloudTexture (clouds.bin, R8, kachelbar)
    Texture* cloudTex = nullptr;
    TextureSampler cloudSampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR, TextureSampler::MagFilter::LINEAR, TextureSampler::WrapMode::REPEAT);
    if (!matDir.empty() && (skyMaterial || L.contains("water"))) {
        auto raw = readFile(skyDir + "/clouds.bin");
        int n = (int)std::lround(std::sqrt((double)raw.size()));
        auto* buf = new uint8_t[raw.size()];
        memcpy(buf, raw.data(), raw.size());
        cloudTex = Texture::Builder().width(n).height(n).levels(0xff).sampler(Texture::Sampler::SAMPLER_2D)
                       .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
                       .format(Texture::InternalFormat::R8).build(*engine);
        cloudTex->setImage(*engine, 0, Texture::PixelBufferDescriptor(buf, raw.size(), Texture::Format::R, Texture::Type::UBYTE,
                                           [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
        cloudTex->generateMipmaps(*engine);
    }
    if (skyMaterial) {
        // Eigenes Himmelsmaterial (app/src/main/materials/sky.mat): Verlauf, Sonne, ziehende Wolken.
        skyMat = loadMaterial(engine, matDir + "/sky.filamat");
        skyMi = skyMat->createInstance();
        const json& M = L["skyMaterial"];
        for (auto it = M.begin(); it != M.end(); ++it) {
            if (it.value().is_array() && it.value().size() == 3) skyMi->setParameter(it.key().c_str(), f3(it.value()));
            else if (it.value().is_array() && it.value().size() == 2) skyMi->setParameter(it.key().c_str(), float2{ it.value()[0].get<float>(), it.value()[1].get<float>() });
            else if (it.value().is_array() && it.value().size() == 4)
                skyMi->setParameter(it.key().c_str(), float4{ it.value()[0].get<float>(), it.value()[1].get<float>(), it.value()[2].get<float>(), it.value()[3].get<float>() });
            else if (it.value().is_number()) skyMi->setParameter(it.key().c_str(), it.value().get<float>());
        }
        skyMi->setParameter("time", float(timeS));
        skyMi->setParameter("clouds", cloudTex, cloudSampler);
        // Vollbild-Dreieck in Geraetekoordinaten (wie Filaments Skybox)
        static const float verts[] = { -1, -1, 1, 1, 3, -1, 1, 1, -1, 3, 1, 1 };
        static const uint16_t idx[] = { 0, 1, 2 };
        auto* vb = VertexBuffer::Builder().vertexCount(3).bufferCount(1)
                       .attribute(VertexAttribute::POSITION, 0, VertexBuffer::AttributeType::FLOAT4, 0, 16).build(*engine);
        vb->setBufferAt(*engine, 0, VertexBuffer::BufferDescriptor(verts, sizeof(verts)));
        auto* ib = IndexBuffer::Builder().indexCount(3).bufferType(IndexBuffer::IndexType::USHORT).build(*engine);
        ib->setBuffer(*engine, IndexBuffer::BufferDescriptor(idx, sizeof(idx)));
        skyE = utils::EntityManager::get().create();
        RenderableManager::Builder(1)
            .geometry(0, RenderableManager::PrimitiveType::TRIANGLES, vb, ib)
            .material(0, skyMi)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .priority(7)
            .build(*engine, skyE);
        scene->addEntity(skyE);
    }

    // ---- Dunst (FilamentCore.applyFog) ----
    {
        View::FogOptions fo;
        fo.enabled = fogOn;
        fo.density = K["fogDensity"].get<float>();
        fo.color = f3(K["fogColor"]);
        fo.height = opt(S, "fogHeight", 0.f);
        fo.heightFalloff = opt(L, "fogHeightFalloff", 0.0012f);
        fo.distance = opt(L, "fogDistance", 30.f);
        fo.maximumOpacity = opt(L, "fogMaxOpacity", 0.92f);
        fo.cutOffDistance = 25000.f * 4.f;
        fo.fogColorFromIbl = false;
        fo.inScatteringStart = opt(L, "fogInScatteringStart", 0.f);
        fo.inScatteringSize = opt(L, "fogInScatteringSize", -1.f);
        view->setFogOptions(fo);
    }

    // ---- View-Einstellungen (FilamentCore.applySettings) ----
    view->setShadowingEnabled(shadows);
    view->setPostProcessingEnabled(post);
    {
        View::MultiSampleAntiAliasingOptions m;
        m.enabled = !taa;
        m.sampleCount = 4;
        view->setMultiSampleAntiAliasingOptions(m);
        view->setAntiAliasing(View::AntiAliasing::NONE);
        View::TemporalAntiAliasingOptions t;
        t.enabled = taa;
        // RenderQuality.taa: etwas mehr Rueckfuehrung gegen Laubflimmern, Varianz-Box gegen Schlieren
        t.feedback = 0.1f;
        t.boxType = View::TemporalAntiAliasingOptions::BoxType::AABB_VARIANCE;
        t.sharpness = 0.25f;
        view->setTemporalAntiAliasingOptions(t);
    }
    const View::QualityLevel fxq = effectQuality >= 2 ? View::QualityLevel::HIGH : effectQuality == 1 ? View::QualityLevel::MEDIUM : View::QualityLevel::LOW;
    {
        // Wie FilamentCore: PCF; DPCF (in 1.75.1 veraltet, faellt auf PCSS = EVSM zurueck) zum Vergleich.
        // Vorgabe PCF wie die App seit dem Geraetebefund (DPCF/PCSS zeigten keine Schatten).
        const std::string st = opt<std::string>(settings, "shadowType", "PCF");
        View::ShadowType t = st == "PCF" ? View::ShadowType::PCF : st == "PCSS" ? View::ShadowType::PCSS
            : st == "VSM" ? View::ShadowType::VSM : st == "PCFd" ? View::ShadowType::PCFd : View::ShadowType::DPCF;
        view->setShadowType(shadows ? t : View::ShadowType::PCF);
    }
    {
        View::SoftShadowOptions s;
        s.penumbraScale = 1.5f;
        s.penumbraRatioScale = 1.f;
        view->setSoftShadowOptions(s);
    }
    view->setDithering(post ? View::Dithering::TEMPORAL : View::Dithering::NONE);
    {
        View::AmbientOcclusionOptions a;
        a.enabled = ao;
        a.radius = 0.4f;
        a.intensity = 1.f;
        if (effectQuality >= 1) {
            // RenderQuality.ambientOcclusion
            a.quality = fxq;
            a.lowPassFilter = fxq;
            a.upsampling = fxq;
            a.radius = effectQuality >= 2 ? 0.55f : 0.5f;
            a.intensity = 0.9f;  // wie RenderQuality.applyAmbientOcclusion
        }
        view->setAmbientOcclusionOptions(a);
        View::BloomOptions b;
        b.enabled = bloom;
        b.strength = opt(L, "bloomStrength", 0.08f);
        if (effectQuality >= 1) b.quality = fxq;
        view->setBloomOptions(b);
        View::VignetteOptions vo;
        vo.enabled = vignette;
        vo.midPoint = 0.6f;
        vo.roundness = 0.4f;
        vo.feather = 0.6f;
        vo.color = { 0.f, 0.f, 0.f, 1.f };
        view->setVignetteOptions(vo);
    }
    // Tonwertabbildung und Farbgebung der Stimmung (fehlt: Filaments Standard ACES_LEGACY)
    ColorGrading* grading = nullptr;
    ToneMapper* tm = nullptr;
    if (L.contains("toneMapper")) {
        const std::string t = L["toneMapper"];
        if (t == "AGX") tm = new AgxToneMapper(AgxToneMapper::AgxLook::NONE);
        else if (t == "AGX_PUNCHY") tm = new AgxToneMapper(AgxToneMapper::AgxLook::PUNCHY);
        else if (t == "PBR_NEUTRAL") tm = new PBRNeutralToneMapper();
        else if (t == "ACES") tm = new ACESToneMapper();
        else if (t == "FILMIC") tm = new FilmicToneMapper();
        else if (t == "GT7") tm = new GT7ToneMapper();
        else tm = new ACESLegacyToneMapper();
        ColorGrading::Builder gb;
        gb.toneMapper(tm)
            .exposure(opt(L, "gradeExposure", 0.f))
            .contrast(opt(L, "contrast", 1.f))
            .vibrance(opt(L, "vibrance", 1.f))
            .saturation(opt(L, "saturation", 1.f))
            .whiteBalance(whiteBalance ? opt(L, "wbTemperature", 0.f) : opt(L, "temperature", 0.f),
                whiteBalance ? opt(L, "wbTint", 0.f) : opt(L, "tint", 0.f));
        if (L.contains("shadowsTint")) {
            gb.shadowsMidtonesHighlights(float4{ f3(L["shadowsTint"]), 0.f }, float4{ 1, 1, 1, 0 }, float4{ 1, 1, 1, 0 }, float4{ 0.f, 0.333f, 0.55f, 1.f });
        }
        grading = gb.build(*engine);
        view->setColorGrading(grading);
    }

    // ---- Welt ----
    auto* wp = new WorldProvider(gltfio::createUbershaderProvider(engine, UBERARCHIVE_DEFAULT_DATA, UBERARCHIVE_DEFAULT_SIZE));
    gltfio::MaterialProvider* mp = wp;
    // Boden-Detail und Wind (terrain.mat, foliage.mat), wenn die Stimmung sie nennt und die
    // Materialien da sind; `settings.groundDetail` / `settings.wind` = false schaltet sie ab
    // wie die Qualitaetsstufe auf dem Geraet (Material bleibt, Parameter auf 0).
    Texture* detailTex = nullptr;
    TextureSampler detailSampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR, TextureSampler::MagFilter::LINEAR, TextureSampler::WrapMode::REPEAT);
    detailSampler.setAnisotropy(8.f);  // wie FilamentCore: eigene Textur, also geht hier Anisotropie
    const bool groundDetail = opt(settings, "groundDetail", true);
    const bool wind = opt(settings, "wind", true);
    auto setParams = [&](MaterialInstance* mi, const json& M) {
        for (auto it = M.begin(); it != M.end(); ++it) {
            if (it.value().is_array() && it.value().size() == 3) mi->setParameter(it.key().c_str(), f3(it.value()));
            else if (it.value().is_array() && it.value().size() == 2) mi->setParameter(it.key().c_str(), float2{ it.value()[0].get<float>(), it.value()[1].get<float>() });
            else if (it.value().is_number()) mi->setParameter(it.key().c_str(), it.value().get<float>());
        }
    };
    if (!matDir.empty() && L.contains("terrain") && opt(settings, "terrainMaterial", true) && std::ifstream(matDir + "/terrain.filamat")) {
        wp->mats[TERRAIN] = loadMaterial(engine, matDir + "/terrain.filamat");
        auto raw = readFile(skyDir + "/detail.bin");
        int n = (int)std::lround(std::sqrt((double)raw.size() / 4));
        auto* buf = new uint8_t[raw.size()];
        memcpy(buf, raw.data(), raw.size());
        detailTex = Texture::Builder().width(n).height(n).levels(0xff).sampler(Texture::Sampler::SAMPLER_2D)
                        .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
                        .format(Texture::InternalFormat::RGBA8).build(*engine);
        detailTex->setImage(*engine, 0, Texture::PixelBufferDescriptor(buf, raw.size(), Texture::Format::RGBA, Texture::Type::UBYTE,
                                            [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
        detailTex->generateMipmaps(*engine);
        if (!cloudTex) {
            auto rc = readFile(skyDir + "/clouds.bin");
            int m = (int)std::lround(std::sqrt((double)rc.size()));
            auto* cb = new uint8_t[rc.size()];
            memcpy(cb, rc.data(), rc.size());
            cloudTex = Texture::Builder().width(m).height(m).levels(0xff).sampler(Texture::Sampler::SAMPLER_2D)
                           .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
                           .format(Texture::InternalFormat::R8).build(*engine);
            cloudTex->setImage(*engine, 0, Texture::PixelBufferDescriptor(cb, rc.size(), Texture::Format::R, Texture::Type::UBYTE,
                                               [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
            cloudTex->generateMipmaps(*engine);
        }
    }
    if (!matDir.empty() && L.contains("foliage") && opt(settings, "foliageMaterial", true) && std::ifstream(matDir + "/foliage.filamat")) {
        wp->mats[FOLIAGE] = loadMaterial(engine, matDir + "/foliage.filamat");
    }
    if (detailTex && L.contains("surface") && opt(settings, "surfaceMaterial", true) && std::ifstream(matDir + "/surface.filamat")) {
        wp->mats[SURFACE] = loadMaterial(engine, matDir + "/surface.filamat");
        wp->mats[SURFACE_BLEND] = loadMaterial(engine, matDir + "/surface_blend.filamat");
    }
    // Nahdetail der Fahrbahn (surface.mat, SurfaceNearDetail in der App): Karten und Parameter
    // aus assets/indoor/surface-detail (nahdetail.py). `settings.nearDetail` = false nur fuer
    // Vorher-Bilder; `groundDetail` = false schaltet es wie auf dem Geraet ab.
    json nearMeta;
    std::map<std::string, Texture*> nearMaps;
    const bool nearDetail = opt(settings, "nearDetail", true) && groundDetail;
    const std::string nearDir = matDir.empty() ? std::string() : matDir + "/../surface-detail";
    if (wp->mats[SURFACE] && std::ifstream(nearDir + "/detail.json")) nearMeta = readJson(nearDir + "/detail.json");
    auto makeTex = [&](int w, int h, const uint8_t* rgba) {
        auto* buf = new uint8_t[size_t(w) * h * 4];
        memcpy(buf, rgba, size_t(w) * h * 4);
        Texture* t = Texture::Builder().width(w).height(h).levels(0xff).sampler(Texture::Sampler::SAMPLER_2D)
                         .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
                         .format(Texture::InternalFormat::RGBA8).build(*engine);
        t->setImage(*engine, 0, Texture::PixelBufferDescriptor(buf, size_t(w) * h * 4, Texture::Format::RGBA, Texture::Type::UBYTE,
                                    [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
        t->generateMipmaps(*engine);
        return t;
    };
    auto nearMap = [&](const std::string& map) -> Texture* {
        auto it = nearMaps.find(map);
        if (it != nearMaps.end()) return it->second;
        Texture* t = nullptr;
        if (!map.empty()) {
            auto raw = readFile(nearDir + "/" + nearMeta["maps"][map]["file"].get<std::string>());
            int w = 0, h = 0, ch = 0;
            uint8_t* px = stbi_load_from_memory(raw.data(), (int)raw.size(), &w, &h, &ch, 4);
            if (px) { t = makeTex(w, h, px); stbi_image_free(px); }
        }
        if (!t) {  // wie die App: immer etwas gebunden (neutral: kein Gefaelle, mittlere Hoehe)
            const uint8_t flat[4] = { 128, 128, 128, 255 };
            t = makeTex(1, 1, flat);
        }
        nearMaps[map] = t;
        return t;
    };
    auto setupNear = [&](MaterialInstance* mi, const std::string& label) {
        std::string key = nearMeta.is_null() ? std::string() : nearKey(label, nearMeta["kinds"]);
        if (key.empty() || !nearDetail) {
            mi->setParameter("nearMap", nearMap(""), detailSampler);
            return;
        }
        const json& k = nearMeta["kinds"][key];
        mi->setParameter("nearMap", nearMap(k["map"].get<std::string>()), detailSampler);
        mi->setParameter("nearA", float4{ nearMeta["rangeM"].get<float>(), k["normal"].get<float>(), k["albedo"].get<float>(), k["relief"].get<float>() });
        mi->setParameter("nearB", float4{ k["repeatM"].get<float>(), k["rough"].get<float>(), k["roughVar"].get<float>(), k["trackGloss"].get<float>() });
        mi->setParameter("nearC", float4{ k["trackU"].get<float>(), nearMeta["reliefRangeM"].get<float>(), opt(k, "reliefTexels", 1.0f), opt(k, "reliefMip", 0.0f) });
    };
    // PBR-Texturen (PbrTextures in der App, pbr.py): Textur-Arrays (KTX1, ETC2/EAC) aus
    // assets/indoor/pbr, je Gruppe erst bei Bedarf. `settings.pbr` = false nur fuer Vorher-Bilder;
    // `groundDetail` = false schaltet sie wie auf dem Geraet ab.
    json pbrMeta;
    std::map<std::string, std::pair<Texture*, Texture*>> pbrGroups;
    const bool pbrOn = opt(settings, "pbr", true) && groundDetail;
    // FILARENDER_PBR_DIR: entpackte Kopie (pbr.py decode) fuer lavapipe-Vulkan, das kein ETC2 kann
    const char* pbrEnv = getenv("FILARENDER_PBR_DIR");
    const std::string pbrDir = pbrEnv ? std::string(pbrEnv) : matDir.empty() ? std::string() : matDir + "/../pbr";
    if (std::ifstream(pbrDir + "/pbr.json")) pbrMeta = readJson(pbrDir + "/pbr.json");
    TextureSampler pbrSampler(TextureSampler::MinFilter::LINEAR_MIPMAP_LINEAR, TextureSampler::MagFilter::LINEAR, TextureSampler::WrapMode::REPEAT);
    pbrSampler.setAnisotropy(8.f);
    auto loadKtx = [&](const std::string& file) -> Texture* {
        auto raw = readFile(pbrDir + "/" + file);
        if (raw.size() < 64) return nullptr;
        uint32_t h[13];
        memcpy(h, raw.data() + 12, sizeof h);
        const uint32_t ifmt = h[4], w = h[6], layers = std::max(1u, h[9]), levels = h[11];
        const bool compressed = h[1] == 0;
        Texture::InternalFormat tf = ifmt == 0x9279 ? Texture::InternalFormat::ETC2_EAC_SRGBA8
            : ifmt == 0x9272 ? Texture::InternalFormat::EAC_RG11 : ifmt == 0x8C43 ? Texture::InternalFormat::SRGB8_A8 : Texture::InternalFormat::RG8;
        Texture::CompressedType ct = ifmt == 0x9279 ? Texture::CompressedType::ETC2_EAC_SRGBA8 : Texture::CompressedType::EAC_RG11;
        // Wie die App: ohne Unterstuetzung nichts bauen (sonst bricht Filament nativ ab)
        if (!Texture::isTextureFormatSupported(*engine, tf)) {
            fprintf(stderr, "PBR: Format %u nicht unterstuetzt (%s)\n", ifmt, file.c_str());
            return nullptr;
        }
        Texture* t = Texture::Builder().width(w).height(w).depth(layers).levels(levels)
                         .sampler(Texture::Sampler::SAMPLER_2D_ARRAY).format(tf).build(*engine);
        size_t off = 64 + h[12];
        for (uint32_t lv = 0; lv < levels; lv++) {
            uint32_t n;
            memcpy(&n, raw.data() + off, 4);
            off += 4;
            auto* buf = new uint8_t[n];
            memcpy(buf, raw.data() + off, n);
            off += (n + 3) & ~3u;
            const uint32_t d = std::max(1u, w >> lv);
            if (compressed)
                t->setImage(*engine, lv, 0, 0, 0, d, d, layers,
                    Texture::PixelBufferDescriptor(buf, n, ct, n, [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
            else
                t->setImage(*engine, lv, 0, 0, 0, d, d, layers,
                    Texture::PixelBufferDescriptor(buf, n, ifmt == 0x8C43 ? Texture::Format::RGBA : Texture::Format::RG, Texture::Type::UBYTE,
                        [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
        }
        return t;
    };
    Texture* pbrNeutral = nullptr;
    auto pbrGroup = [&](const std::string& g) -> std::pair<Texture*, Texture*> {
        if (g.empty()) {
            if (!pbrNeutral) {
                auto* buf = new uint8_t[4]{ 128, 128, 128, 255 };
                pbrNeutral = Texture::Builder().width(1).height(1).depth(1).levels(1).sampler(Texture::Sampler::SAMPLER_2D_ARRAY)
                                 .format(Texture::InternalFormat::RGBA8).build(*engine);
                pbrNeutral->setImage(*engine, 0, 0, 0, 0, 1, 1, 1, Texture::PixelBufferDescriptor(buf, 4, Texture::Format::RGBA, Texture::Type::UBYTE,
                                                                          [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
            }
            return { pbrNeutral, pbrNeutral };
        }
        auto it = pbrGroups.find(g);
        if (it != pbrGroups.end()) return it->second;
        const json& gm = pbrMeta["groups"][g];
        std::pair<Texture*, Texture*> r{ loadKtx(gm["color"]), loadKtx(gm["normal"]) };
        pbrGroups[g] = r;
        return r;
    };
    auto setupPbr = [&](MaterialInstance* mi, const std::string& label, bool terrain) {
        std::string g;
        const json* k = nullptr;
        if (!pbrMeta.is_null() && pbrOn) {
            if (terrain) { k = &pbrMeta["terrain"]; g = (*k)["group"]; }
            else {
                std::string key = pbrKey(label, pbrMeta["kinds"]);
                if (!key.empty()) { k = &pbrMeta["kinds"][key]; g = (*k)["group"]; }
            }
        }
        auto tex = pbrGroup(g);
        if (!tex.first || !tex.second) { tex = pbrGroup(""); k = nullptr; }
        mi->setParameter("pbrMapC", tex.first, pbrSampler);
        mi->setParameter("pbrMapN", tex.second, pbrSampler);
        if (!k) return;  // pbrA.x = 0 (Vorgabe): aus
        const json& K2 = *k;
        if (terrain) {
            mi->setParameter("pbrA", float4{ pbrMeta["terrainRangeM"].get<float>(), K2["normal"].get<float>(), K2["albedo"].get<float>(), K2["roughMix"].get<float>() });
            mi->setParameter("pbrB", float4{ K2["repeats"][0].get<float>(), K2["repeats"][1].get<float>(), K2["repeats"][2].get<float>(), K2["repeats"][3].get<float>() });
            mi->setParameter("pbrC", float4{ K2["roughScale"].get<float>(), K2["roughBias"].get<float>(), 0.f, 0.f });
        } else {
            mi->setParameter("pbrA", float4{ pbrMeta["rangeM"].get<float>(), K2["normal"].get<float>(), K2["albedo"].get<float>(), K2["replace"].get<float>() });
            mi->setParameter("pbrB", float4{ K2["meters"].get<float>(), K2["layer"].get<float>(), K2["alt"].get<float>(), K2["altMix"].get<float>() });
            mi->setParameter("pbrC", float4{ K2["roughScale"].get<float>(), K2["roughBias"].get<float>(), K2["roughMix"].get<float>(), K2["keepLod"].get<float>() });
            mi->setParameter("pbrD", float4{ K2["coord"].get<float>(), K2["uvMetersU"].get<float>(), K2["uvMetersV"].get<float>(), K2["altMeters"].get<float>() });
        }
    };
    wp->setup = [&](MaterialInstance* mi, int kind, const std::string& label) {
        if (kind == 1) {
            setParams(mi, L["terrain"]);
            if (!groundDetail) mi->setParameter("detailRange", 0.f);
            mi->setParameter("detailMap", detailTex, detailSampler);
            mi->setParameter("macroMap", cloudTex, cloudSampler);
            if (wp->mats[1]->hasParameter("pbrA")) setupPbr(mi, label, true);
            // Software-Anisotropie des Luftbilds (WorldMaterialProvider: an wie PBR, 90 m);
            // `settings.terrainAniso` = false nur fuer Vorher-Bilder.
            if (wp->mats[1]->hasParameter("anisoRange"))
                mi->setParameter("anisoRange", pbrOn && opt(settings, "terrainAniso", true) ? 90.f : 0.f);
        } else if (kind == SURFACE || kind == SURFACE_BLEND) {
            setParams(mi, L["surface"]);
            if (!groundDetail) mi->setParameter("detailRange", 0.f);
            if (!opt(settings, "aniso", true)) mi->setParameter("anisoRange", 0.f);
            mi->setParameter("detailMap", detailTex, detailSampler);
            mi->setParameter("detailClass", detailClass(label));
            if (wp->mats[kind]->hasParameter("nearA")) setupNear(mi, label);
            if (wp->mats[kind]->hasParameter("pbrA")) setupPbr(mi, label, false);
        } else {
            setParams(mi, L["foliage"]);
            if (!wind) mi->setParameter("windStrength", 0.f);
            mi->setParameter("time", float(timeS));
            mi->setParameter("atlasMode", label == "x_mat_tree_cards" ? 1.f : 0.f);
        }
    };
    utils::NameComponentManager names(utils::EntityManager::get());
    gltfio::AssetLoader* loader = gltfio::AssetLoader::create({ engine, mp, &names });
    gltfio::ResourceLoader* res = new gltfio::ResourceLoader({ engine, ".", true });
    gltfio::TextureProvider* stb = gltfio::createStbProvider(engine);
    res->addTextureProvider("image/png", stb);
    res->addTextureProvider("image/jpeg", stb);

    // Wassermaterial (app/src/main/materials/water.mat), wenn die Stimmung es nennt
    Material* waterMat = nullptr;
    MaterialInstance* waterMi = nullptr;
    if (L.contains("water") && !matDir.empty() && opt(settings, "water", true)) {
        waterMat = loadMaterial(engine, matDir + "/water.filamat");
        waterMi = waterMat->createInstance();
        const json& M = L["water"];
        for (auto it = M.begin(); it != M.end(); ++it) {
            // nur deklarierte Parameter (Stimmung und Material koennen verschiedene Staende haben)
            if (!waterMat->hasParameter(it.key().c_str())) continue;
            if (it.value().is_array() && it.value().size() == 3) waterMi->setParameter(it.key().c_str(), f3(it.value()));
            else if (it.value().is_array() && it.value().size() == 2) waterMi->setParameter(it.key().c_str(), float2{ it.value()[0].get<float>(), it.value()[1].get<float>() });
            else if (it.value().is_number()) waterMi->setParameter(it.key().c_str(), it.value().get<float>());
        }
        waterMi->setParameter("time", float(timeS));
        waterMi->setParameter("waves", cloudTex, cloudSampler);
        // Wellen-Normalenkarte (WaterWaves, RGBA8): $WATER_WAVES oder <sky>/water_waves.bin;
        // fehlt sie, eine flache Karte (ruhiges Wasser) statt eines ungebundenen Samplers.
        if (waterMat->hasParameter("waveNormals")) {
            const char* env = std::getenv("WATER_WAVES");
            std::string path = env ? std::string(env) : skyDir + "/water_waves.bin";
            std::vector<uint8_t> raw;
            if (std::ifstream(path)) raw = readFile(path);
            if (raw.empty()) { fprintf(stderr, "water_waves.bin fehlt (%s): flaches Wasser\n", path.c_str()); raw.assign(4 * 4 * 4, (uint8_t)128); }
            int n = (int)std::lround(std::sqrt((double)raw.size() / 4.0));
            auto* buf = new uint8_t[raw.size()];
            memcpy(buf, raw.data(), raw.size());
            Texture* wn = Texture::Builder().width(n).height(n).levels(0xff).sampler(Texture::Sampler::SAMPLER_2D)
                              .usage(Texture::Usage::DEFAULT | Texture::Usage::GEN_MIPMAPPABLE)
                              .format(Texture::InternalFormat::RGBA8).build(*engine);
            wn->setImage(*engine, 0, Texture::PixelBufferDescriptor(buf, raw.size(), Texture::Format::RGBA, Texture::Type::UBYTE,
                                     [](void* b, size_t, void*) { delete[] (uint8_t*)b; }));
            wn->generateMipmaps(*engine);
            waterMi->setParameter("waveNormals", wn, cloudSampler);
        }
        // niedrige Stufe (ohne Boden-Detail): eine Wellenlage, keine gespiegelten Wolken — wie FilamentCore
        if (waterMat->hasParameter("detail") && !opt(settings, "groundDetail", true)) waterMi->setParameter("detail", 0.f);
    }

    const std::string world = S["world"];
    auto& rm = engine->getRenderableManager();
    auto& tmgr = engine->getTransformManager();
    size_t renderables = 0, primitives = 0, waterPrims = 0;
    std::vector<Loaded> tiles;
    for (const auto& t : S["tiles"]) {
        Loaded l = loadAsset(engine, loader, res, world + "/" + t["file"].get<std::string>());
        std::set<std::string> hidden;
        for (const auto& h : t["hidden"]) hidden.insert(h.get<std::string>());
        auto ri = tmgr.getInstance(l.asset->getRoot());
        tmgr.setTransform(ri, translation(f3(t["pos"])));
        for (auto& [e, cat] : l.category) {
            auto r = rm.getInstance(e);
            std::string base = cat.rfind("terrain_sub_", 0) == 0 ? "terrain" : cat;
            bool cast = base == "buildings" || base == "trees" || (terrainShadows && base == "terrain");
            bool recv0 = !(base == "trees" || base == "props");
            // EVSM (PCSS, auf das DPCF in 1.75.1 zurueckfaellt) verlangt: wer empfaengt, wirft auch.
            if (opt(settings, "receiversCast", false) && recv0 && base != "water") cast = true;
            bool recv = !(base == "trees" || base == "props");
            rm.setCastShadows(r, cast && shadows);
            rm.setReceiveShadows(r, recv);
            bool vis = !hidden.count(cat);
            if (base == "trees" && !opt(settings, "trees", true)) vis = false;
            if (base == "props" && !opt(settings, "props", true)) vis = false;
            rm.setLayerMask(r, 0xFF, vis ? 1 : 0);
            if (vis) {
                renderables++;
                primitives += rm.getPrimitiveCount(r);
            }
            if (waterMi && base == "water") {
                for (size_t p = 0; p < rm.getPrimitiveCount(r); p++) rm.setMaterialInstanceAt(r, p, waterMi);
                rm.setCastShadows(r, false);
                waterPrims += rm.getPrimitiveCount(r);
            }
        }
        scene->addEntities(l.asset->getEntities(), l.asset->getEntityCount());
        tiles.push_back(l);
    }

    // ---- Fahrer / Cockpit ----
    auto placeModel = [&](const json& R) {
        Loaded l = loadAsset(engine, loader, res, R["file"].get<std::string>());
        tmgr.setTransform(tmgr.getInstance(l.asset->getRoot()), matFromJson(R["matrix"]));
        for (auto& [e, cat] : l.category) {
            auto r = rm.getInstance(e);
            rm.setCastShadows(r, shadows);
            rm.setReceiveShadows(r, true);
            renderables++;
            primitives += rm.getPrimitiveCount(r);
        }
        if (R.contains("nodes")) {
            for (auto it = R["nodes"].begin(); it != R["nodes"].end(); ++it) {
                Entity e = l.asset->getFirstEntityByName(it.key().c_str());
                if (!e.isNull()) tmgr.setTransform(tmgr.getInstance(e), matFromJson(it.value()));
            }
        }
        gltfio::Animator* an = l.asset->getInstance()->getAnimator();
        if (an) {
            for (size_t i = 0; i < an->getAnimationCount(); i++) {
                std::string n = an->getAnimationName(i);
                float d = an->getAnimationDuration(i);
                if (n == "pedal") an->applyAnimation(i, float(opt(R, "pedal", 0.15)) * d);
                if (n == "roll") an->applyAnimation(i, float(opt(R, "roll", 0.0)) * d);
            }
            an->updateBoneMatrices();
        }
        scene->addEntities(l.asset->getEntities(), l.asset->getEntityCount());
    };
    if (S.contains("rider") && !S["rider"].is_null()) placeModel(S["rider"]);
    // Weitere Modelle: andere Radfahrer (umgefaerbte rider.glb), Voegel (birds.glb)
    if (S.contains("models")) for (const auto& M : S["models"]) placeModel(M);
    if (S.contains("cockpit") && !S["cockpit"].is_null()) placeModel(S["cockpit"]);

    auto tLoad = std::chrono::steady_clock::now();

    // ---- Zeichnen ----
    std::vector<uint8_t> px(size_t(W) * H * 4);
    bool done = false;
    const int frames = opt(S, "frames", 3);
    for (int i = 0; i < frames; i++) {
        engine->flushAndWait();
        if (!renderer->beginFrame(sc)) continue;
        renderer->render(view);
        if (i == frames - 1) {
            renderer->readPixels(0, 0, W, H,
                backend::PixelBufferDescriptor(px.data(), px.size(), backend::PixelDataFormat::RGBA, backend::PixelDataType::UBYTE,
                    [](void*, size_t, void* u) { *(bool*)u = true; }, &done));
        }
        renderer->endFrame();
    }
    for (int k = 0; k < 200 && !done; k++) {
        engine->flushAndWait();
        engine->pumpMessageQueues();
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (!done) {
        fprintf(stderr, "filarender: kein Bild gelesen\n");
        return 5;
    }
    // Kopfstehend? Der Kopflose Swapchain von PlatformGLX liefert die oberste Zeile zuerst.
    stbi_write_png(argv[2], W, H, 4, px.data(), W * 4);
    auto tEnd = std::chrono::steady_clock::now();
    auto ms = [](auto a, auto b) { return (long)std::chrono::duration_cast<std::chrono::milliseconds>(b - a).count(); };
    printf("{\"renderables\": %zu, \"primitives\": %zu, \"waterPrimitives\": %zu, \"terrainMi\": %zu, \"foliageMi\": %zu, \"surfaceMi\": %zu, \"uberMi\": %zu, \"loadMs\": %ld, \"renderMs\": %ld}\n",
        renderables, primitives, waterPrims, wp->counts[TERRAIN], wp->counts[FOLIAGE], wp->counts[SURFACE] + wp->counts[SURFACE_BLEND], wp->counts[UBER], ms(t0, tLoad), ms(tLoad, tEnd));
    fflush(stdout);
    // Aufraeumen ueberlassen wir dem Prozessende (Werkzeug, kein Dauerlauf).
    _exit(0);
}
