# Event contract fixtures

Golden examples of the events described in `PROJECT_BRIEF.md` (Kafka Event Contract). There is no
shared Java contract module: the producer owns the schema and each consumer keeps its own tolerant
reader, so these files are what keeps them in agreement.

- The producer's tests assert that what it publishes has the shape of `patient.created.v1.json`.
- Every consumer's tests parse both files; `patient.created.v1-unknown-field.json` proves a
  consumer ignores fields added later within the same version.

Values are synthetic. Change a fixture only together with the contract text in `PROJECT_BRIEF.md`.
