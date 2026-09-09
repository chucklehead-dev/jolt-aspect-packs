# Changelog

## Unreleased

- Follow chDB Durable control operations through their option-bearing retry
  arities, preserving one history/fault event for both convenience-wrapper and
  writer-direct calls against jolt-chdb `e2c4fd0` and its target-owned
  `4a0b821` seam epoch.
