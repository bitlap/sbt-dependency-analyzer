#!/usr/bin/env bash
# Sign and publish through sbt-idea-plugin. Default mode performs offline source checks only.
set +x
set -euo pipefail

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
VALIDATOR="$ROOT/tools/validate-plugin-release.py"
mode=dry-run
mode_set=false
channel=
sbt_bin=sbt
env_file=${XDG_CONFIG_HOME:-$HOME/.config}/sbt-dependency-analyzer/credentials.env
explicit_env=false

usage() {
  cat <<'EOF'
Usage: tools/upload-plugin.sh [--dry-run | --build | --upload]
       [--channel Stable|eap] [--env-file FILE] [--sbt EXECUTABLE]

  --dry-run  Validate source version/change-notes without building or uploading (default).
  --build    Sign with sbt signPlugin and validate the resulting ZIP; do not upload.
  --upload   Sign, validate, then upload that exact ZIP with sbt publishPlugin.

Local configuration: ~/.config/sbt-dependency-analyzer/credentials.env (if present).
Signing: PLUGIN_SIGN_CERT_FILE, PLUGIN_SIGN_PRIVATE_KEY_FILE, optional PLUGIN_SIGN_KEY_PWD.
Token: ~/.ij-plugin-repo-token, otherwise IJ_PLUGIN_REPO_TOKEN or JETBRAINS_MARKETPLACE_TOKEN.
EOF
}
die() { printf 'Error: %s\n' "$*" >&2; exit 1; }
set_mode() {
  if $mode_set && [[ $mode != "$1" ]]; then die 'Choose only one execution mode'; fi
  mode=$1
  mode_set=true
}
while (($#)); do
  case $1 in
    --dry-run|--build|--upload) set_mode "${1#--}"; shift ;;
    --channel|--env-file|--sbt)
      (($# >= 2)) && [[ -n $2 ]] || die "$1 requires a value"
      case $1 in
        --channel) channel=$2 ;;
        --env-file) env_file=$2; explicit_env=true ;;
        --sbt) sbt_bin=$2 ;;
      esac
      shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done
case $channel in [Ss][Tt][Aa][Bb][Ll][Ee]) channel= ;; esac
[[ -z $channel || $channel =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]*$ ]] || die 'Invalid channel name'
command -v python3 >/dev/null || die 'Python 3 is required for offline XML/ZIP checks'
cd -- "$ROOT"
version=$(python3 "$VALIDATOR" --version-only)
printf 'Plugin version: %s; channel: %s; mode: %s\n' "$version" "${channel:-Stable}" "$mode"
if [[ $mode == dry-run ]]; then
  printf 'Source version/change-notes valid. No build or upload performed.\n'
  exit 0
fi

if [[ -f $env_file ]]; then
  # This is a trusted, user-owned shell configuration; never enable tracing while sourcing secrets.
  set -a
  source "$env_file"
  set +a
elif $explicit_env; then
  die "Configuration file not found: $env_file"
fi
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
command -v "$sbt_bin" >/dev/null || die 'sbt not found; add it to PATH or pass --sbt /path/to/sbt'
[[ -r ${PLUGIN_SIGN_CERT_FILE:-} ]] || die 'Set PLUGIN_SIGN_CERT_FILE to a readable certificate chain'
[[ -r ${PLUGIN_SIGN_PRIVATE_KEY_FILE:-} ]] || die 'Set PLUGIN_SIGN_PRIVATE_KEY_FILE to a readable private key'
export SIGN_PLUGIN=true
if [[ $mode == upload ]]; then
  # sbt-idea-plugin gives its token file precedence over environment variables.
  if [[ -s $HOME/.ij-plugin-repo-token ]]; then
    IFS= read -r IJ_PLUGIN_REPO_TOKEN < "$HOME/.ij-plugin-repo-token" || true
  else
    IJ_PLUGIN_REPO_TOKEN=${IJ_PLUGIN_REPO_TOKEN:-${JETBRAINS_MARKETPLACE_TOKEN:-}}
  fi
  [[ -n ${IJ_PLUGIN_REPO_TOKEN:-} && ! $IJ_PLUGIN_REPO_TOKEN =~ [[:space:]] ]] || die 'Configure a non-empty Marketplace token without whitespace'
  export IJ_PLUGIN_REPO_TOKEN
fi

run_sbt() {
  # pipefail preserves sbt failures; the filter keeps credentials out of terminal/CI output.
  local sbt_command=("$sbt_bin")
  if [[ -n ${JAVA_HOME:-} ]]; then sbt_command+=(-java-home "$JAVA_HOME"); fi
  "${sbt_command[@]}" "$@" </dev/null 2>&1 | python3 "$VALIDATOR" --redact-stdin
}
run_sbt signPlugin
archive="$ROOT/target/signed-SbtDependencyAnalyzer-$version.zip"
python3 "$VALIDATOR" "$archive"
if [[ $mode == build ]]; then
  printf 'Signed ZIP validated. No upload performed.\n'
  exit 0
fi

# Pin publishPlugin to the validated signed ZIP, avoiding another build/re-sign after validation.
# JSON escaping is also valid for this ordinary Scala string literal; never interpolate shell text as sbt code.
pin=$(python3 -c 'import json,sys; print("set signPlugin := file(" + json.dumps(sys.argv[1], ensure_ascii=True) + ")")' "$archive")
if run_sbt "$pin" "publishPlugin${channel:+ $channel}"; then
  printf 'Marketplace accepted the signed plugin. Check its review/release status in the vendor dashboard.\n'
else
  status=$?
  printf 'Upload failed; no automatic retry. Check Marketplace before trying again.\n' >&2
  exit "$status"
fi
