#!/usr/bin/env bash
# Spawn-time protocol of the producer feasibility gate (WP01 T009, plan 2): the SAME script runs unchanged on all three legs
# (Git Bash on Windows). 25 consecutive spawns of the same stdin payload; the first 5 are discarded; the threshold applies to the
# MEDIAN of the last 20; the p95 of the last 20 and the cold (first) spawn are recorded.
#
#   measure-start.sh <binary> [--payload FILE] [--max-median-ms N]
#
# Prints one JSON object on stdout. Exit 0 when the median is within --max-median-ms (default 100), 1 when it is not, 2 when the
# measurement itself could not run (missing binary, a spawn exiting non-zero, no perl).
set -euo pipefail

binary=""
payload=""
max_median_ms=100
while [ $# -gt 0 ]; do
  case "$1" in
    --payload) payload="$2"; shift 2 ;;
    --max-median-ms) max_median_ms="$2"; shift 2 ;;
    -*) echo "measure-start: unknown option $1" >&2; exit 2 ;;
    *) binary="$1"; shift ;;
  esac
done

if [ -z "$binary" ] || [ ! -f "$binary" ]; then
  echo "measure-start: binary not found: '$binary'" >&2
  exit 2
fi
command -v perl >/dev/null 2>&1 || { echo "measure-start: perl is required for the timer" >&2; exit 2; }

cleanup=""
if [ -z "$payload" ]; then
  payload="$(mktemp)"
  cleanup="$payload"
  printf '%s\n' '{"v":1,"op":"produce","start_path":"."}' > "$payload"
fi
trap '[ -z "$cleanup" ] || rm -f "$cleanup"' EXIT

runs=25
discard=5
exec perl -MTime::HiRes=gettimeofday,tv_interval -e '
  my ($bin, $payload, $runs, $discard, $max) = @ARGV;
  my @ms;
  for my $i (1 .. $runs) {
    open(my $in, "<", $payload) or die "payload: $!";
    my $t0 = [gettimeofday];
    my $pid = fork();
    die "fork: $!" unless defined $pid;
    if ($pid == 0) {
      open(STDIN, "<&", $in) or exit 126;
      open(STDOUT, ">", "/dev/null") or exit 126;
      exec { $bin } $bin;
      exit 127;
    }
    waitpid($pid, 0);
    my $elapsed = tv_interval($t0) * 1000.0;
    if ($? != 0) { print STDERR "measure-start: spawn $i exited with status $?\n"; exit 2; }
    push @ms, $elapsed;
  }
  my @kept = sort { $a <=> $b } @ms[$discard .. $#ms];
  my $n = scalar @kept;
  my $median = ($n % 2) ? $kept[($n - 1) / 2] : ($kept[$n / 2 - 1] + $kept[$n / 2]) / 2;
  my $p95 = $kept[int(0.95 * ($n - 1) + 0.5)];
  printf("{\"runs\":%d,\"discarded\":%d,\"cold_ms\":%.1f,\"median_ms\":%.1f,\"p95_ms\":%.1f,\"min_ms\":%.1f,\"max_median_ms\":%d}\n",
    $runs, $discard, $ms[0], $median, $p95, $kept[0], $max);
  exit($median <= $max ? 0 : 1);
' "$binary" "$payload" "$runs" "$discard" "$max_median_ms"
