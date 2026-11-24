# Erzeugt app/src/main/materials/surface.mat und surface_blend.mat aus einer Vorlage (gleicher
# Shader, andere Mischung). Danach materials.sh. Aufruf: python3 tool/indoor-dev/filament/surface-mats.py
import os
MAT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "app", "src", "main", "materials")
COMMON_HEAD = '''// {title}
// ersetzt die gltfio-Materialien {which} (WorldMaterialProvider; derselbe Tausch in filarender).
// Aussehen wie der Ubershader (Grundfarbe × Textur × Vertexfarbe), aber:
//
//  * Software-Anisotropie nah am Fahrer: gltfio 1.75.1 uebernimmt keine Anisotropie aus dem
//    glTF-Sampler, und die Java-Seite gibt die Texturen nicht heraus — flach gesehene Belaege
//    verschwammen schon wenige Meter vor dem Rad. Bis `anisoRange` Meter wird die Textur
//    viermal entlang der Hauptachse der Pixelspur abgetastet (textureLod mit der Mip-Stufe der
//    kurzen Achse), danach ein einzelner Zugriff wie bisher.
//  * Boden-Detail (GroundDetail) auf den Randstreifen: Bodenart je Material (`detailClass`,
//    z. B. Wiese oder Waldboden), Helligkeit und Detail-Normale wie terrain.mat; Detail-UV aus
//    der Weltlage modulo 5,12 m (die Weltlage bleibt dank schwebendem Ursprung unter rund
//    2,5 km — highp genuegt; gleiche Phase wie das Gelaende, weil Ursprung und Kacheln auf
//    256 m = 50 × 5,12 m liegen).
//
// Erzeugt von tool/indoor-dev/filament/surface-mats.py (nicht von Hand aendern), kompilieren:
// tool/indoor-dev/filament/materials.sh (matc 1.75.1).
material {{
    name : {name},
    requires : [ uv0, color ],
    parameters : [
        {{ type : sampler2d, name : baseColorMap }},
        {{ type : float4, name : baseColorFactor }},
        {{ type : float3, name : emissiveFactor }},
        {{ type : float, name : metallicFactor }},
        {{ type : float, name : roughnessFactor }},
        {{ type : float, name : normalScale }},
        {{ type : float, name : aoStrength }},
        {{ type : sampler2d, name : detailMap, precision : medium }},
        {{ type : float4, name : detailClass }},
        {{ type : float, name : detailRange }},
        {{ type : float, name : detailStrength }},
        {{ type : float, name : bumpStrength }},
        {{ type : float, name : anisoRange }},
        {{ type : float, name : forestWarmth }}
    ],
    shadingModel : lit,
    blending : {blending},
{extra}    flipUV : false,
    featureLevel : 1
}}

fragment {{
    // Vier Abtastungen entlang der langen Achse der Pixelspur, Mip-Stufe nach der kurzen Achse.
    vec4 sampleAniso(highp vec2 uv, float near) {{
        highp vec2 dx = dFdx(uv);
        highp vec2 dy = dFdy(uv);
        vec2 ts = vec2(textureSize(materialParams_baseColorMap, 0));
        vec2 px = dx * ts;
        vec2 py = dy * ts;
        float lx = dot(px, px);
        float ly = dot(py, py);
        float lmaj = sqrt(max(lx, ly));
        float lmin = sqrt(min(lx, ly));
        float ratio = lmaj / max(lmin, 1e-4);
        if (near <= 0.0 || ratio < 1.6) return texture(materialParams_baseColorMap, uv);
        float taps = min(ratio, 4.0);
        float lod = max(log2(lmaj / taps), 0.0);
        highp vec2 major = lx > ly ? dx : dy;
        vec4 c = vec4(0.0);
        for (int k = 0; k < 4; k++) {{
            float t = (float(k) + 0.5) / 4.0 - 0.5;
            c += textureLod(materialParams_baseColorMap, uv + major * t, lod);
        }}
        return mix(texture(materialParams_baseColorMap, uv), c * 0.25, near);
    }}

    void material(inout MaterialInputs material) {{
        highp vec2 uv = getUV0();
        highp vec3 wp = getWorldPosition();
        float dist = length(wp - getWorldCameraPosition());
        float ar = materialParams.anisoRange;
        float near = ar > 0.0 ? 1.0 - smoothstep(ar * 0.7, ar, dist) : 0.0;
        vec4 c = sampleAniso(uv, near) * materialParams.baseColorFactor * getColor();

        // Waldboden waermer (wie terrain.mat): Randstreifen im Wald sind ans kuehle Luftbild angeglichen.
        vec4 cls0 = materialParams.detailClass;
        float wf = cls0.z / max(cls0.x + cls0.y + cls0.z + cls0.w, 1e-3);
        float lumc = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
        c.rgb = mix(c.rgb, vec3(lumc) * vec3(1.42, 1.0, 0.62) * 1.08, materialParams.forestWarmth * wf);
        // Dunkles Blaugruen (an das Winter-Luftbild angeglichene Raender) zu Gruenbraun
        float mxc = max(c.r, max(c.g, c.b));
        float cool = smoothstep(0.0, 0.22, (c.b - c.r) / max(mxc, 0.004)) * (1.0 - smoothstep(0.06, 0.16, lumc));
        c.rgb = mix(c.rgb, vec3(lumc) * vec3(1.12, 1.06, 0.66) * 1.05, 0.65 * cool * step(0.001, materialParams.forestWarmth));

        vec3 nTs = vec3(0.0, 0.0, 1.0);
        vec4 cls = materialParams.detailClass;
        float range = materialParams.detailRange;
        float fade = range > 0.0 ? 1.0 - smoothstep(range * 0.55, range, dist) : 0.0;
        float clsSum = cls.x + cls.y + cls.z + cls.w;
        if (fade > 0.001 && clsSum > 0.0) {{
            highp vec3 uw = getUserWorldPosition();
            highp vec2 duv = fract(uw.xz / 5.12);
            const float texel = 1.0 / 256.0;
            vec4 h0 = texture(materialParams_detailMap, duv);
            vec4 hx = texture(materialParams_detailMap, duv + vec2(texel, 0.0));
            vec4 hy = texture(materialParams_detailMap, duv + vec2(0.0, texel));
            float h = dot(h0, cls);
            float gx = dot(hx - h0, cls);
            float gy = dot(hy - h0, cls);
            c.rgb *= max(1.0 + materialParams.detailStrength * fade * (h - 0.5) * 2.0, 0.0);
            vec3 nGeo = normalize(getWorldTangentFrame()[2]);
            vec3 p = -(gx * vec3(1.0, 0.0, 0.0) + gy * vec3(0.0, 0.0, 1.0)) * (materialParams.bumpStrength * fade);
            p -= dot(p, nGeo) * nGeo;
            vec3 nW = normalize(nGeo + p);
            nTs = vec3(dot(nW, getWorldTangentFrame()[0]), dot(nW, getWorldTangentFrame()[1]), dot(nW, getWorldTangentFrame()[2]));
        }}
        material.normal = nTs;
        prepareMaterial(material);
        material.baseColor = {base};
        material.metallic = 0.0;
        material.roughness = materialParams.roughnessFactor;
    }}
}}
'''
open(os.path.join(MAT, 'surface.mat'),'w').write(COMMON_HEAD.format(
  title='Belaege und Randstreifen der Indoor-Darstellung (deckend):',
  which='der Fahrbahnen (`mat_route_*`, `x_mat_road_*`) und deckenden Randstreifen\n// (`x_mat_route_verge_*`, Bankett, Gehweg)',
  name='TrailscapeSurface', blending='opaque', extra='', base='vec4(c.rgb, 1.0)'))
open(os.path.join(MAT, 'surface_blend.mat'),'w').write(COMMON_HEAD.format(
  title='Auslaeufe der Randstreifen der Indoor-Darstellung (durchsichtig):',
  which='`x_mat_route_*_fade` (Auslauf des Uebergangsstreifens ins Luftbild) und\n// `x_mat_route_fringe` (Graskante)',
  name='TrailscapeSurfaceBlend', blending='transparent', extra='    depthWrite : false,\n', base='vec4(c.rgb * c.a, c.a)'))
