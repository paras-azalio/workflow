#!/bin/sh
# Drop-folder reply reader (command.reply.default): the reply to mail MESSAGE_ID is the file
# $SHARED_DATA_DIR/mail/replies/<MESSAGE_ID>.txt - an optional first line "From: <sender>", then the text, whose
# first word is the decision when it is one of OPTIONS (comma-separated, e.g. APPROVED,REJECTED). No file yet, or a
# text that does not start with an option (e.g. an out-of-office answer): DECISION=PENDING, keep waiting.
# Answers DECISION, TEXT, FROM, SUBJECT ("Re: " + MAIL_SUBJECT) and RECEIVED_AT (yyyyMMddHHmmss, the file's time).
#
#   sh reply-drop.sh <request file> <response file>
set -eu
request="$1"
response="$2"
replies="${SHARED_DATA_DIR:?SHARED_DATA_DIR is not set}/mail/replies"
prop() { sed -n "s/^$1=//p" "$request" | head -1 | sed 's/\\\([,:=#!]\)/\1/g'; }
id=$(prop MESSAGE_ID)
options=$(prop OPTIONS)
if [ -z "$id" ]; then
  printf 'STATUS=FAILED\nMESSAGE=No MESSAGE_ID to read a reply for.\n' > "$response"
  exit 0
fi
file="$replies/$id.txt"
if [ ! -f "$file" ]; then
  printf 'STATUS=SUCCESS\nMESSAGE=No reply to %s yet.\nDECISION=PENDING\n' "$id" > "$response"
  exit 0
fi
from=$(tr -d '\r' < "$file" | sed -n '1s/^From:[[:space:]]*//p')
[ -n "$from" ] || from="reply-drop"
body=$(tr -d '\r' < "$file" | sed '1{/^From:/d;}')
word=$(printf '%s\n' "$body" | awk 'NF {w = toupper($1); sub(/[^A-Z0-9_].*$/, "", w); print w; exit}')
text=$(printf '%s' "$body" | tr '\n' ' ' | cut -c1-2000)
case ",$options," in
  *",$word,"*)
    {
      printf 'STATUS=SUCCESS\nMESSAGE=Reply to %s: %s\nDECISION=%s\n' "$id" "$word" "$word"
      printf 'TEXT=%s\nFROM=%s\nSUBJECT=Re: %s\nRECEIVED_AT=%s\n' "$text" "$from" "$(prop MAIL_SUBJECT)" "$(date -r "$file" +%Y%m%d%H%M%S)"
    } > "$response" ;;
  *)
    printf 'STATUS=SUCCESS\nMESSAGE=The reply to %s does not start with one of %s; still waiting.\nDECISION=PENDING\nTEXT=%s\n' \
      "$id" "$options" "$text" > "$response" ;;
esac
