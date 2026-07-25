# StoRpt

StoRpt is a personal PWA for filling historical China A-share prices into a
strictly compatible Excel workbook. The MVP product baseline is documented in
[`docs/product-design.md`](docs/product-design.md).

## Current stage

Technical validation and the backend core are complete. The current stage adds
the authenticated Vue PWA, two-upload workflow, SSE task progress, and
single-use download delivery.

No local Java, Maven, Node.js, or Docker installation is required. The
technical validation is designed to run in GitHub Actions.

## Remote validation

1. Publish this directory as a GitHub repository.
2. Open the repository's **Actions** page.
3. Use **Excel technical validation**, **FastAPI backend validation**, and
   **PWA frontend validation** as the Java, Python integration, and frontend
   release gates.
4. Treat any failed workflow as a release blocker.

The validation scope and remaining exit criteria are tracked in
[`docs/technical-validation.md`](docs/technical-validation.md).

## Authentication configuration

The service does not store a plaintext access password. Generate a scrypt hash
using the bundled backend module, then configure the hash and an independent
random signing secret in the deployment environment:

```sh
cd backend
python -m storpt_api.auth
```

- `STORPT_PASSWORD_HASH`: output from the command above.
- `STORPT_SESSION_SECRET`: a long random value used only for signing the
  seven-day session cookie.

If either variable is missing, health checks remain available but login fails
closed with `SYSTEM-003`.
