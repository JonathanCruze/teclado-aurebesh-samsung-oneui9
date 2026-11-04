#!/usr/bin/env bash
# Compila el motor (app/src/main/java/com/aurebesh/teclado/llm) y las pruebas de esta carpeta con
# 'javac -source 8 -target 8 -nowarn' y ejecuta toda la verificacion fuera del telefono:
#   0) QuantTest: cada nucleo int8 contra la descuantizacion exacta
#   1) make_random_gguf.py: modelos aleatorios llama/qwen2/qwen3 con F32/F16/Q8_0/Q4_0/Q4_1/Q5_0/Q5_1/Q4_K/Q5_K/Q6_K
#   2) reference.py: logits de referencia en numpy
#   3) RefTest: logits del motor Java (exacto e int8, secuencia completa e incremental) contra la referencia
#   4) TokenizerTest: BPE Java contra ref_tokenize.py y los fixtures de llama.cpp sobre ggml-vocab-qwen2 / llama-bpe
#   5) Bench (con --bench): tokens/s en un modelo con la forma de Qwen2.5-0.5B en Q8_0 y Q4_0
#
# Variables: PYTHON (interprete con gguf+numpy+regex), LLM_SCRATCH (carpeta de trabajo; los modelos
# aleatorios se escriben ahi, nunca en el repositorio), LLM_VOCABS (carpeta con ggml-vocab-*.gguf[.inp/.out]),
# THREADS (hilos del banco, 4 por defecto).
set -euo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
PYTHON=${PYTHON:-python3}
SCRATCH=${LLM_SCRATCH:-${TMPDIR:-/tmp}/aurebesh-llm}
VOCABS=${LLM_VOCABS:-$SCRATCH/vocabs}
THREADS=${THREADS:-4}
BENCH=0
for a in "$@"; do [ "$a" = "--bench" ] && BENCH=1; done
mkdir -p "$SCRATCH/classes" "$SCRATCH/models"
STATUS=0

echo "## Compilacion (javac -source 8 -target 8 -nowarn)"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options -d "$SCRATCH/classes" \
  "$ROOT"/app/src/main/java/com/aurebesh/teclado/llm/*.java "$ROOT"/tools/llm/*.java
CP="$SCRATCH/classes"
JAVA="java -Xss4m -Dfile.encoding=UTF-8 -cp $CP"

echo "## Nucleos de producto escalar (int8 vs exacto)"
$JAVA com.aurebesh.teclado.llm.QuantTest 1024 64 || STATUS=1

echo "## Modelos aleatorios"
"$PYTHON" tools/llm/make_random_gguf.py tiny "$SCRATCH/models"

IDS="5 17 99 3 200 42 7 128 255"
for m in tiny-qwen3 tiny-qwen2 tiny-llama tiny-llama-qkv tiny-qwen2-f16; do
  echo "## Referencia numpy y RefTest: $m"
  "$PYTHON" tools/llm/reference.py "$SCRATCH/models/$m.gguf" "$SCRATCH/models/$m.ref.txt" $IDS
  $JAVA com.aurebesh.teclado.llm.RefTest "$SCRATCH/models/$m.gguf" "$SCRATCH/models/$m.ref.txt" $IDS || STATUS=1
done

for v in qwen2 llama-bpe; do
  F="$VOCABS/ggml-vocab-$v.gguf"
  if [ ! -f "$F" ]; then echo "## Tokenizador $v: falta $F (se omite)"; continue; fi
  echo "## Tokenizador: $v"
  "$PYTHON" tools/llm/ref_tokenize.py "$F" tools/llm/sentences.txt "$SCRATCH/tok-$v.ref.txt"
  FIX=""
  [ -f "$F.inp" ] && [ -f "$F.out" ] && FIX="$F.inp $F.out"
  $JAVA com.aurebesh.teclado.llm.TokenizerTest "$F" tools/llm/sentences.txt "$SCRATCH/tok-$v.ref.txt" $FIX || STATUS=1
done

if [ "$BENCH" = 1 ]; then
  echo "## Banco de velocidad (forma de Qwen2.5-0.5B, $THREADS hilos)"
  [ -f "$SCRATCH/models/bench-qwen2-q8_0.gguf" ] || "$PYTHON" tools/llm/make_random_gguf.py bench "$SCRATCH/models"
  for q in q8_0 q4_0; do
    $JAVA com.aurebesh.teclado.llm.Bench "$SCRATCH/models/bench-qwen2-$q.gguf" "$THREADS" 64 16 || STATUS=1
  done
fi

if [ $STATUS = 0 ]; then echo "## TODO OK"; else echo "## HAY FALLOS"; fi
exit $STATUS
