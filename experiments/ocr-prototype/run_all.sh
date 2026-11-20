#!/bin/bash
# Sequential benchmark on the test machine (one job at a time so timings are not disturbed). Run from the code dir:  bash run_all.sh
cd "$(dirname "$0")"; R=${OCR_ROOT:-$HOME/ocr-experiment}; PY=$R/env/bin/python; mkdir -p $R/out
run() { tag=$1; shift; /usr/bin/time -v -o $R/out/$tag.time "$@" >> $R/out/run_all.log 2>&1; echo "finished $tag $(date +%T)" >> $R/out/run_all.log; }
python3 prep_inputs.py >/dev/null 2>&1 || $PY prep_inputs.py
for d in A B C; do run pdftotext_$d $PY run_engine.py pdftotext $d; done
for d in A B C; do run tesseract_$d $PY run_engine.py tesseract $d --threads 1; done
run tesseract_A_par6 $PY run_engine.py tesseract A --threads 1 --procs 6 --tag _par6
run tesseract_A_2cpu taskset -c 0,2 $PY run_engine.py tesseract A --threads 1 --tag _2cpu
for d in A B C; do run rapidocr_$d $PY run_engine.py rapidocr $d --threads 6; done
run rapidocr_A_t12 $PY run_engine.py rapidocr A --threads 12 --tag _t12
run rapidocr_A_2cpu taskset -c 0,2 $PY run_engine.py rapidocr A --threads 2 --tag _2cpu
export LD_LIBRARY_PATH=$R/llama
for d in A B C; do run deepseek_$d $PY run_engine.py deepseek $d --threads 6; done
DS_PROMPT="<|grounding|>Convert the document to markdown." run deepseek_A_md $PY run_engine.py deepseek A --threads 6 --tag _md
run deepseek_A_t12 $PY run_engine.py deepseek A --threads 12 --pages 21,81,138 --tag _t12
run deepseek_A_2cpu taskset -c 0,2 $PY run_engine.py deepseek A --threads 2 --pages 21,81 --tag _2cpu
echo ALLDONE >> $R/out/run_all.log
