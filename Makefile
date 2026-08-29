JOLT ?= jolt
JOLT_ASPECT_JOLT ?=

.PHONY: test db-source-test glitter-source-test glimmer-source-test http-server-source-test aspect-smoke core-async-aspect-smoke core-async-fault-smoke core-async-plain-smoke db-aspect-smoke db-plain-smoke http-client-aspect-smoke http-server-aspect-smoke glitter-aspect-smoke glimmer-aspect-smoke mycelium-aspect-smoke mycelium-plain-smoke

test:
	$(JOLT) -M:test

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

db-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/db && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.db \
	    -o target/db-aspect-scenario
	@scenarios/db/target/db-aspect-scenario
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

http-server-aspect-smoke:
	@test -n "$(JOLT_ASPECT_JOLT)" || \
	  (echo "JOLT_ASPECT_JOLT must be an absolute path to an aspect-capable jolt" >&2; exit 2)
	@cd scenarios/http-server && \
	  "$(JOLT_ASPECT_JOLT)" build -m jolt.aspect-packs.scenario.http-server \
	    -o target/http-server-aspect-scenario
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

db-source-test:
	$(JOLT) -M:db-conformance
