JOLT ?= jolt
JOLT_ASPECT_JOLT ?=

.PHONY: test glitter-source-test aspect-smoke http-client-aspect-smoke glitter-aspect-smoke

test:
	$(JOLT) -M:test

glitter-source-test:
	$(JOLT) -M:glitter-conformance

aspect-smoke: http-client-aspect-smoke glitter-aspect-smoke

http-client-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/http-client && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.http-client \
	    -o target/http-client-aspect-scenario
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.http-client.report-test \
	  scenarios/http-client/target/aspects.edn

glitter-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/glitter && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.glitter \
	    -o target/glitter-aspect-scenario
	@"$(JOLT_ASPECT_JOLT)" -Srepro \
	  -Sdeps '{:paths ["test"]}' \
	  -m jolt.aspect-packs.glitter.report-test \
	  scenarios/glitter/target/aspects.edn
