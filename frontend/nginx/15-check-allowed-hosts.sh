#!/bin/sh
# Runs from the nginx image's entrypoint before the config template is rendered.
# ALLOWED_HOSTS ends up in nginx's server_name, so accept only
#   - host names and IPv4 addresses (dot-separated labels of letters, digits, "-" and "_"),
#   - the wildcard forms nginx supports: "*.lan" (leading) or "chess.*" (trailing), on a dot border,
#   - bracketed IPv6 addresses ("[fd00::1]": up to eight hex groups, at most one "::"),
# and stop the container with a clear message otherwise.
set -eu
set -f # entries like *.lan must not be expanded as file globs

fail() {
  echo "$0: invalid ALLOWED_HOSTS entry '$1': use space-separated host names, IPv4 addresses, [IPv6] addresses or *.name / name.* wildcards, without scheme or port, e.g. ALLOWED_HOSTS=\"chess-server 192.168.1.20\"" >&2
  exit 1
}

# Dot-separated labels, each non-empty: letters, digits, "-" and "_".
valid_name() {
  case "$1" in
    '' | .* | *. | *..* | *[!A-Za-z0-9._-]*) return 1 ;;
  esac
  return 0
}

valid_ipv6() {
  addr="$1"
  case "$addr" in
    # empty, a lone colon, other characters, ":::", two "::", or a single colon at either end
    '' | : | *[!0-9A-Fa-f:]* | *:::* | *::*::* | :[!:]* | *[!:]:) return 1 ;;
  esac
  case "$addr" in
    *::*) compressed=1 ;;
    *) compressed=0 ;;
  esac
  groups=0
  old_ifs="$IFS"
  IFS=:
  for group in $addr; do
    [ -z "$group" ] && continue
    case "$group" in ?????*) IFS="$old_ifs"; return 1 ;; esac
    groups=$((groups + 1))
  done
  IFS="$old_ifs"
  # "::" stands for at least one zero group.
  if [ "$compressed" -eq 1 ]; then
    [ "$groups" -le 7 ]
  else
    [ "$groups" -eq 8 ]
  fi
}

for host in ${ALLOWED_HOSTS:-}; do
  case "$host" in
    \[*\])
      inner="${host#\[}"
      inner="${inner%\]}"
      valid_ipv6 "$inner" || fail "$host"
      ;;
    \*.*) valid_name "${host#\*.}" || fail "$host" ;;
    *.\*) valid_name "${host%.\*}" || fail "$host" ;;
    *) valid_name "$host" || fail "$host" ;;
  esac
done

echo "$0: allowed hosts: localhost 127.0.0.1 [::1]${ALLOWED_HOSTS:+ $ALLOWED_HOSTS}"
