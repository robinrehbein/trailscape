# R8-Regeln des Release-Builds (zusaetzlich zu proguard-android-optimize.txt).
#
# Grundhaltung: so wenig wie moeglich. Jede Zeile hier haelt Code am Leben,
# den R8 sonst entfernen wuerde — Regeln ohne nachgewiesenen Grund kosten
# APK-Groesse und verstecken echte Probleme. Was hier NICHT steht und warum:
#
#  * kotlinx-serialization: :core und :app bauen JSON ausschliesslich von Hand
#    ueber JsonObject/JsonPrimitive auf (siehe core/.../JsonSupport.kt). Es gibt
#    keine @Serializable-Klasse, keinen generierten Serializer und damit auch
#    keine Reflexion, die R8 uebersehen koennte. Die bekannten
#    -keep-Regeln fuer @Serializable-Typen waeren hier reine Zierde.
#  * MapLibre, OkHttp, Health Connect, AndroidX/Compose: alle
#    liefern ihre notwendigen Regeln als consumer-rules in der AAR/JAR mit,
#    AGP bindet sie automatisch ein. Insbesondere haelt MapLibre selbst alles,
#    was ueber JNI angesprochen wird, am Leben.
#  * de.trailscape.core / de.trailscape.app: gewoehnlicher Kotlin-Code, den R8
#    ueber die Aufrufkette vom Einstiegspunkt aus erreicht. Nicht Erreichtes
#    darf und soll verschwinden.
#  * MainActivity, TrailscapeApplication, RecordingService, FileProvider: im
#    Manifest genannt, AGP erzeugt dafuer automatisch -keep-Regeln.

# Zeilennummern erhalten und die Quelldatei auf einen festen Platzhalter
# setzen. Ohne das sind Stacktraces aus dem Feld nicht mehr rueckuebersetzbar;
# die Zuordnung steht in build/outputs/mapping/release/mapping.txt, die der
# Build-Workflow als Artefakt sichert.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
