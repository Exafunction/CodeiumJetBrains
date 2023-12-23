#!/bin/bash

set -euxo pipefail

cd "$(dirname "$0")"

buf generate proto \
  --path proto/exa/chat_web_server_pb/chat_web_server.proto \
  --path proto/exa/language_server_pb/language_server.proto \
  --path proto/exa/seat_management_pb/seat_management.proto \
  --include-imports
