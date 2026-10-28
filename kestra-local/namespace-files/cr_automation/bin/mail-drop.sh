#!/bin/sh
# Drop-folder mail sender for environments without a mail server (command.mail.default): the mail - the request
# file as is (TO, CC, SUBJECT, BODY, ATTACHMENTS, ORDER_ID, CHILD_ID, NODE, ITEM, ...) - goes to
# $SHARED_DATA_DIR/mail/outbox/<MESSAGE_ID>.properties. Replace it with the real mail program of the environment.
#
#   sh mail-drop.sh <request file> <response file>
set -eu
request="$1"
response="$2"
outbox="${SHARED_DATA_DIR:?SHARED_DATA_DIR is not set}/mail/outbox"
mkdir -p "$outbox"
id="mail-$(date +%Y%m%d%H%M%S)-$$-$(od -An -N4 -tx4 /dev/urandom | tr -d ' ')"
if ! grep -q '^TO=.' "$request"; then
  printf 'STATUS=FAILED\nMESSAGE=No recipient (TO) given.\n' > "$response"
  exit 0
fi
cp "$request" "$outbox/$id.properties"
printf 'STATUS=SUCCESS\nMESSAGE=Mail %s written to the outbox.\nMESSAGE_ID=%s\n' "$id" "$id" > "$response"
echo "mail $id -> $outbox"
