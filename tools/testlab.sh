#!/usr/bin/env bash
# Ejecuta la app en Galaxy reales de Firebase Test Lab (prueba Robo) y deja capturas y vídeo en la consola.
# Requisitos: un proyecto Firebase con Test Lab y una cuenta de servicio con el rol "Firebase Test Lab Admin".
#   FIREBASE_PROJECT=id-del-proyecto
#   GOOGLE_APPLICATION_CREDENTIALS=/ruta/clave.json   (o GOOGLE_APPLICATION_CREDENTIALS_JSON con el contenido,
#                                                       por ejemplo como secreto del entorno de Claude Code)
#   DEVICES="model=ID,version=33;model=ID2,version=34"  (opcional; por defecto el primer Galaxy físico disponible)
set -euo pipefail
cd "$(dirname "$0")/.."
: "${FIREBASE_PROJECT:?Define FIREBASE_PROJECT}"
if [ -n "${GOOGLE_APPLICATION_CREDENTIALS_JSON:-}" ]; then
  umask 077; printf '%s' "$GOOGLE_APPLICATION_CREDENTIALS_JSON" > "${TMPDIR:-/tmp}/testlab-sa.json"
  export GOOGLE_APPLICATION_CREDENTIALS="${TMPDIR:-/tmp}/testlab-sa.json"
fi
: "${GOOGLE_APPLICATION_CREDENTIALS:?Define GOOGLE_APPLICATION_CREDENTIALS o GOOGLE_APPLICATION_CREDENTIALS_JSON}"
gcloud auth activate-service-account --key-file="$GOOGLE_APPLICATION_CREDENTIALS" >/dev/null
gcloud config set project "$FIREBASE_PROJECT" >/dev/null
if [ -z "${DEVICES:-}" ]; then
  MODEL=$(gcloud firebase test android models list --filter="manufacturer=Samsung AND form=PHYSICAL" --format="value(id)" | head -1)
  VER=$(gcloud firebase test android models describe "$MODEL" --format="value(supportedVersionIds)" | tr ';' '\n' | tail -1)
  DEVICES="model=$MODEL,version=$VER"
fi
ARGS=()
IFS=';' read -ra DEVS <<<"$DEVICES"
for d in "${DEVS[@]}"; do ARGS+=(--device "$d,locale=es,orientation=portrait"); done
RUN="aurebesh-$(date +%Y%m%d-%H%M%S)"
mkdir -p screenshots/testlab
gcloud firebase test android run --type robo --app TecladoAurebesh.apk --timeout 3m \
  --results-dir "$RUN" "${ARGS[@]}" --format=json | tee "screenshots/testlab/$RUN.json"
echo "Capturas y vídeo: https://console.firebase.google.com/project/$FIREBASE_PROJECT/testlab/histories"
