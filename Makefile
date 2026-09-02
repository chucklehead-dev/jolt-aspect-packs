JOLT ?= jolt
JOLT_ASPECT_JOLT ?=
CHECKPOINT_TIMEOUT ?= timeout

define assert-effect-report
	@sh test/assert-effect-report.sh "$(JOLT_ASPECT_JOLT)" "$(1)" "$(2)" "$(3)"
endef

.PHONY: test checkpoint-replay-proof checkpoint-runtime-history db-source-test glitter-source-test glimmer-source-test http-server-source-test aspect-smoke core-async-aspect-smoke core-async-fault-smoke core-async-plain-smoke db-aspect-smoke db-plain-smoke http-client-aspect-smoke http-server-aspect-smoke glitter-aspect-smoke glimmer-aspect-smoke mycelium-aspect-smoke mycelium-plain-smoke jolt-regression-matrix jolt-regression-matrix-self-test jolt-regression-coverage jolt-regression-coverage-live

test: checkpoint-replay-proof
	$(JOLT) -M:test

checkpoint-replay-proof:
	@sh test/formal/check-checkpoint-replay.sh

checkpoint-runtime-history:
	@test -n "$(JOLT_CHECKPOINT_JOLT)" || \
	  (echo "JOLT_CHECKPOINT_JOLT must be an absolute path to the checkpoint-capable jolt" >&2; exit 2)
	@test -n "$(JOLT_CHECKPOINT_SOURCE)" || \
	  (echo "JOLT_CHECKPOINT_SOURCE must be the exact checkpoint-capable Jolt source root" >&2; exit 2)
	@test -n "$(JOLT_CHEZ)" || \
	  (echo "JOLT_CHEZ must name the pinned Chez compiler" >&2; exit 2)
	@set -eu; \
	  case "$(JOLT_CHECKPOINT_JOLT)" in /*) ;; *) echo "JOLT_CHECKPOINT_JOLT must be absolute" >&2; exit 2;; esac; \
	  case "$(JOLT_CHECKPOINT_SOURCE)" in /*) ;; *) echo "JOLT_CHECKPOINT_SOURCE must be absolute" >&2; exit 2;; esac; \
	  test -x "$(JOLT_CHECKPOINT_JOLT)" || (echo "checkpoint Jolt is not executable" >&2; exit 2); \
	  command -v "$(CHECKPOINT_TIMEOUT)" >/dev/null || \
	    (echo "checkpoint runtime gate requires $(CHECKPOINT_TIMEOUT)" >&2; exit 2); \
	  test "$$(git -C "$(JOLT_CHECKPOINT_SOURCE)" rev-parse --is-inside-work-tree 2>/dev/null)" = true || \
	    (echo "checkpoint source is not a Git checkout" >&2; exit 2); \
	  test -z "$$(git -C "$(JOLT_CHECKPOINT_SOURCE)" status --porcelain --untracked-files=no)" || \
	    (echo "checkpoint Jolt source has tracked changes" >&2; exit 2); \
	  revision=$$(git -C "$(JOLT_CHECKPOINT_SOURCE)" rev-parse HEAD); \
	  short=$$(git -C "$(JOLT_CHECKPOINT_SOURCE)" rev-parse --short=8 HEAD); \
	  version=$$("$(JOLT_CHECKPOINT_JOLT)" --version); \
	  case "$$version" in *dirty*) echo "checkpoint Jolt binary is dirty: $$version" >&2; exit 2;; esac; \
	  case "$$version" in *-g$$short) ;; *) echo "checkpoint source/binary revision mismatch: $$short / $$version" >&2; exit 2;; esac; \
	  snapshot=$$(mktemp); \
	  liveness=$$(mktemp); \
	  trap 'rm -f "$$snapshot" "$$liveness"' EXIT; \
	  cd "$(JOLT_CHECKPOINT_SOURCE)"; \
	  if "$(CHECKPOINT_TIMEOUT)" 15s "$(JOLT_CHEZ)" --script \
	       "$(CURDIR)/test/fixtures/checkpoint-runtime/producer.ss" \
	       --crashed-worker >/dev/null 2>"$$liveness"; then \
	    echo "crashed-worker producer unexpectedly succeeded" >&2; exit 1; \
	  else liveness_status=$$?; fi; \
	  test "$$liveness_status" -ne 124 || \
	    (echo "crashed-worker producer exceeded process deadline" >&2; cat "$$liveness" >&2; exit 1); \
	  grep -q "intentional worker crash observed after join" "$$liveness" || \
	    (echo "crashed-worker producer did not report the expected crash" >&2; cat "$$liveness" >&2; exit 1); \
	  grep -q "crashed worker joined" "$$liveness" || \
	    (echo "crashed-worker producer did not join its worker" >&2; cat "$$liveness" >&2; exit 1); \
	  "$(CHECKPOINT_TIMEOUT)" 60s "$(JOLT_CHEZ)" --script "$(CURDIR)/test/fixtures/checkpoint-runtime/producer.ss" >"$$snapshot"; \
	  cd "$(CURDIR)"; \
	  "$(CHECKPOINT_TIMEOUT)" 30s "$(JOLT_CHECKPOINT_JOLT)" -Srepro \
	    -Sdeps '{:paths ["src" "test"]}' -M \
	    -m jolt.aspect-packs.checkpoint-runtime-integration-test "$$snapshot" "$$revision"

jolt-regression-matrix:
	@test -n "$(JOLT_UNFIXED)" || \
	  (echo "JOLT_UNFIXED must be an absolute path to an upstream/unfixed jolt" >&2; exit 2)
	@test -n "$(JOLT_FIXED)" || \
	  (echo "JOLT_FIXED must be an absolute path to a fixed jolt" >&2; exit 2)
	@JOLT_UNFIXED="$(JOLT_UNFIXED)" JOLT_FIXED="$(JOLT_FIXED)" \
	  bb regressions/jolt/run.bb

jolt-regression-matrix-self-test:
	@chmod +x test/fixtures/regression-matrix/fixed-jolt \
	  test/fixtures/regression-matrix/unfixed-jolt \
	  test/fixtures/regression-matrix/upstream-fixed-jolt \
	  test/fixtures/regression-matrix/malformed-jolt
	@report=$$(mktemp); \
	  trap 'rm -f "$$report"' EXIT; \
	  JOLT_UNFIXED="$$PWD/test/fixtures/regression-matrix/unfixed-jolt" \
	  JOLT_FIXED="$$PWD/test/fixtures/regression-matrix/fixed-jolt" \
	    bb regressions/jolt/run.bb >"$$report"; \
	  bb -e '(let [r (read-string (slurp (first *command-line-args*)))] (assert (:ok? r)) (assert (= {:pass 18 :fail 18 :xpass 0 :error 0} (:summary r))))' "$$report"
	@report=$$(mktemp); \
	  trap 'rm -f "$$report"' EXIT; \
	  JOLT_UNFIXED="$$PWD/test/fixtures/regression-matrix/upstream-fixed-jolt" \
	  JOLT_FIXED="$$PWD/test/fixtures/regression-matrix/fixed-jolt" \
	    bb regressions/jolt/run.bb >"$$report"; \
	  bb -e '(let [r (read-string (slurp (first *command-line-args*)))] (assert (:ok? r)) (assert (= {:pass 18 :fail 0 :xpass 18 :error 0} (:summary r))))' "$$report"
	@if JOLT_UNFIXED="$$PWD/test/fixtures/regression-matrix/unfixed-jolt" \
	  JOLT_FIXED="$$PWD/test/fixtures/regression-matrix/malformed-jolt" \
	  bb regressions/jolt/run.bb >/dev/null; then \
	    echo "malformed fixed binary unexpectedly passed" >&2; exit 1; \
	  fi
	@if OMIT_ISSUE_11_STDERR=1 \
	  JOLT_UNFIXED="$$PWD/test/fixtures/regression-matrix/unfixed-jolt" \
	  JOLT_FIXED="$$PWD/test/fixtures/regression-matrix/fixed-jolt" \
	  bb regressions/jolt/run.bb >/dev/null; then \
	    echo "missing required stderr diagnostic unexpectedly passed" >&2; exit 1; \
	  fi
	@if ISSUE_11_STDOUT_ONLY=1 \
	  JOLT_UNFIXED="$$PWD/test/fixtures/regression-matrix/unfixed-jolt" \
	  JOLT_FIXED="$$PWD/test/fixtures/regression-matrix/fixed-jolt" \
	  bb regressions/jolt/run.bb >/dev/null; then \
	    echo "stdout diagnostic unexpectedly satisfied stderr contract" >&2; exit 1; \
	  fi

jolt-regression-coverage:
	@$(JOLT) -M -m jolt.aspect-packs.regression-coverage \
	  regressions/jolt/cases.edn regressions/jolt/fork-fixed-coverage.edn

jolt-regression-coverage-live:
	@command -v gh >/dev/null || \
	  (echo "gh is required for the live fork-fixed coverage check" >&2; exit 2)
	@set -eu; \
	  live=$$(mktemp); \
	  trap 'rm -f "$$live"' EXIT; \
	  gh issue list --repo chucklehead-dev/jolt-aspect-packs --state open \
	    --label status:fixed-in-fork --limit 1001 --json number \
	    --jq 'if length > 1000 then error("fixed-in-fork query exceeds 1000 issues") else map(.number) | sort end' \
	    >"$$live"; \
	  $(JOLT) -M -m jolt.aspect-packs.regression-coverage \
	    regressions/jolt/cases.edn regressions/jolt/fork-fixed-coverage.edn "$$live"

glitter-source-test:
	$(JOLT) -M:glitter-conformance

glimmer-source-test:
	$(JOLT) -M:glimmer-conformance

http-server-source-test:
	$(JOLT) -M:http-server-conformance

aspect-smoke: core-async-aspect-smoke core-async-fault-smoke core-async-plain-smoke db-aspect-smoke db-plain-smoke http-client-aspect-smoke http-server-aspect-smoke glitter-aspect-smoke glimmer-aspect-smoke mycelium-aspect-smoke mycelium-plain-smoke

core-async-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/core-async && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.core-async \
	    -o target/core-async-aspect-scenario
	@scenarios/core-async/target/core-async-aspect-scenario
	$(call assert-effect-report,scenarios/core-async/target/core-async-aspect-scenario.build/effects.edn,woven,scenarios/core-async/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test" "src"]}' \
	  -m jolt.aspect-packs.core-async.report-test \
	  scenarios/core-async/target/aspects.edn

core-async-fault-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/core-async-faults && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.core-async \
	    -o target/core-async-fault-scenario
	@scenarios/core-async-faults/target/core-async-fault-scenario
	$(call assert-effect-report,scenarios/core-async-faults/target/core-async-fault-scenario.build/effects.edn,woven,scenarios/core-async-faults/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test" "src"]}' \
	  -m jolt.aspect-packs.core-async.fault-report-test \
	  scenarios/core-async-faults/target/aspects.edn

core-async-plain-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/core-async-plain && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.core-async \
	    -o target/core-async-plain-scenario
	@scenarios/core-async-plain/target/core-async-plain-scenario plain
	$(call assert-effect-report,scenarios/core-async-plain/target/core-async-plain-scenario.build/effects.edn,plain)

db-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/db && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.db \
	    -o target/db-aspect-scenario
	@scenarios/db/target/db-aspect-scenario
	$(call assert-effect-report,scenarios/db/target/db-aspect-scenario.build/effects.edn,woven,scenarios/db/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test" "src"] :deps {io.github.casselc/db {:git/url "https://github.com/casselc/db.git" :git/sha "0c559d78d839f2f9c8cc1a7326a639134134bfac"}}}' \
	  -m jolt.aspect-packs.db.report-test \
	  scenarios/db/target/aspects.edn

db-plain-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/db-plain && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.db \
	    -o target/db-plain-scenario
	@scenarios/db-plain/target/db-plain-scenario plain
	$(call assert-effect-report,scenarios/db-plain/target/db-plain-scenario.build/effects.edn,plain)

http-client-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/http-client && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.http-client \
	    -o target/http-client-aspect-scenario
	$(call assert-effect-report,scenarios/http-client/target/http-client-aspect-scenario.build/effects.edn,woven,scenarios/http-client/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.http-client.report-test \
	  scenarios/http-client/target/aspects.edn

http-server-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/http-server && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.http-server \
	    -o target/http-server-aspect-scenario
	$(call assert-effect-report,scenarios/http-server/target/http-server-aspect-scenario.build/effects.edn,woven,scenarios/http-server/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.http-server.report-test \
	  scenarios/http-server/target/aspects.edn

glitter-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/glitter && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.glitter \
	    -o target/glitter-aspect-scenario
	$(call assert-effect-report,scenarios/glitter/target/glitter-aspect-scenario.build/effects.edn,woven,scenarios/glitter/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.glitter.report-test \
	  scenarios/glitter/target/aspects.edn

glimmer-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/glimmer && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.glimmer \
	    -o target/glimmer-aspect-scenario
	@scenarios/glimmer/target/glimmer-aspect-scenario
	$(call assert-effect-report,scenarios/glimmer/target/glimmer-aspect-scenario.build/effects.edn,woven,scenarios/glimmer/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.glimmer.report-test \
	  scenarios/glimmer/target/aspects.edn

mycelium-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/mycelium && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.mycelium \
	    -o target/mycelium-aspect-scenario
	@scenarios/mycelium/target/mycelium-aspect-scenario
	$(call assert-effect-report,scenarios/mycelium/target/mycelium-aspect-scenario.build/effects.edn,woven,scenarios/mycelium/target/aspects.edn)
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test" "src"]}' \
	  -m jolt.aspect-packs.mycelium.report-test \
	  scenarios/mycelium/target/aspects.edn

mycelium-plain-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/mycelium-plain && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.mycelium \
	    -o target/mycelium-plain-scenario
	@scenarios/mycelium-plain/target/mycelium-plain-scenario plain
	$(call assert-effect-report,scenarios/mycelium-plain/target/mycelium-plain-scenario.build/effects.edn,plain)

db-source-test:
	$(JOLT) -M:db-conformance
