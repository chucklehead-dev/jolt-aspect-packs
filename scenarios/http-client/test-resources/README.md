# Local TLS fixture

`cert.pem` and `key.pem` are the public, test-only self-signed localhost pair
from `casselc/http-client@eab6b78d5957f88690faf6768360572a3f185341`.
They let the compatibility scenario exercise the same OpenSSL server path as
the maintained provider without network access or generated test state.

The private key authenticates no deployed service and is intentionally checked
in beside its certificate solely for hermetic loopback tests.
