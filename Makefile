JOLT ?= jolt
JOLT_ASPECT_JOLT ?=

.PHONY: test aspect-smoke

test:
	$(JOLT) -M:test

aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/http-client && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.http-client \
	    -o target/http-client-aspect-scenario
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.http-client.report-test \
	  scenarios/http-client/target/aspects.edn
