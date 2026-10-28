#!/bin/sh
# Drop-folder reply reader (command.reply.default): the reply to mail MESSAGE_ID is the file
# $SHARED_DATA_DIR/mail/replies/<MESSAGE_ID>.txt; its first word is the decision when it is one of OPTIONS
# (comma-separated, e.g. APPROVED,REJECTED), the rest is kept as TEXT. No file yet: DECISION=PENDING.
#
#   sh reply-drop.sh <request file> <response file>
set -eu
request="$1"
response="$2"
replies="${SHARED_DATA_DIR:?SHARED_DATA_DIR is not set}/mail/replies"
id=$(sed -n 's/^MESSAGE_ID=//p' "$request" | head -1)
options=$(sed -n 's/^OPTIONS=//p' "$request" | head -1 | sed 's/\\,/,/g')
if [ -z "$id" ]; then
  printf 'STATUS=FAILED\nMESSAGE=No MESSAGE_ID to read a reply for.\n' > "$response"
  exit 0
fi
file="$replies/$id.txt"
if [ ! -f "$file" ]; then
  printf 'STATUS=SUCCESS\nMESSAGE=No reply to %s yet.\nDECISION=PENDING\n' "$id" > "$response"
  exit 0
fi
word=$(tr -d '\r' < "$file" | awk 'NF {w = toupper($1); sub(/[^A-Z0-9_].*$/, "", w); print w; exit}')
text=$(tr -d '\r' < "$file" | tr '\n' ' ' | cut -c1-500)
case ",$options," in
  *",$word,"*)
    printf 'STATUS=SUCCESS\nMESSAGE=Reply to %s: %s\nDECISION=%s\nTEXT=%s\n' "$id" "$word" "$word" "$text" > "$response" ;;
  *)   # e.g. an out-of-office answer: not a decision, keep waiting
    printf 'STATUS=SUCCESS\nMESSAGE=The reply to %s does not start with one of %s; still waiting.\nDECISION=PENDING\nTEXT=%s\n' \
      "$id" "$options" "$text" > "$response" ;;
esac
