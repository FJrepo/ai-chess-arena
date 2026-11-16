#!/bin/sh
# Runs from the nginx image's entrypoint before the config template is rendered.
# ALLOWED_HOSTS ends up in nginx's server_name, so accept only host names, IPv4 addresses, bracketed IPv6
# addresses and nginx wildcards (*.lan), and stop the container with a clear message otherwise.
set -eu
set -f # entries like *.lan must not be expanded as file globs

fail() {
  echo "$0: invalid ALLOWED_HOSTS entry '$1': use space-separated host names or IP addresses without scheme or port, e.g. ALLOWED_HOSTS=\"chess-server 192.168.1.20\"" >&2
  exit 1
}

for host in ${ALLOWED_HOSTS:-}; do
  case "$host" in
    \[*\])
      inner="${host#\[}"
      inner="${inner%\]}"
      case "$inner" in
        '' | *[!0-9A-Fa-f:.]*) fail "$host" ;;
      esac
      ;;
    *[!A-Za-z0-9.*_-]*) fail "$host" ;;
  esac
done

echo "$0: allowed hosts: localhost 127.0.0.1 [::1]${ALLOWED_HOSTS:+ $ALLOWED_HOSTS}"
