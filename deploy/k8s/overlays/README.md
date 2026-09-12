# K8S overlays

`java-production/` is the deployment-layer overlay for the Java 25 Center and
React console. It is intentionally separate from the reusable
`../java-center` base so the base can remain placeholder-safe.

Before a production sync:

1. Run the GitHub Native Release workflow and complete all JVM, PostgreSQL,
   native, smoke, checksum and attestation gates.
2. Replace the two image tags in `java-production/kustomization.yaml` and the
   Center version in `java-production/center-production-patch.yaml` with the
   verified release version. For production, prefer an immutable image digest.
3. Create `remote-connect-mcp-java-secrets` outside Git with the MCP/Admin
   tokens and PostgreSQL connection values. Do not store an Enrollment Token
   in the Center Secret; issue one per machine through the Admin API and use it
   only during that Agent registration.
4. Render and inspect the result before applying:

   ```sh
   bash scripts/validate-java-production-overlay.sh --strict deploy/k8s/overlays/java-production
   kustomize build deploy/k8s/overlays/java-production
   ```

   The checked-in overlay is intentionally a public template and will fail
   `--strict` until it is copied into a private deployment layer with real
   `remote-connect-mcp-*` hostnames, immutable image digests and an external
   Secret. CI runs the non-deploying `--template` mode to ensure those
   placeholders remain safe in the public repository.

The overlay does not alter the existing Go Deployment or ChatGPT connector;
it is a migration candidate until the separate canary and rollback acceptance
is complete.
