#!/bin/sh
file_list=""
while [ $# -gt 0 ]; do
  case "$1" in
    --file-list) file_list="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf '['
first=1
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if [ $first -eq 0 ]; then printf ','; fi
  first=0
  printf '{"path":"%s","score":10.0,"review":[]}' "$f"
done < "$file_list"
printf ']'
