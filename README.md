# StoRpt

StoRpt is a personal PWA for filling historical China A-share prices into a
strictly compatible Excel workbook. The MVP product baseline is documented in
[`docs/product-design.md`](docs/product-design.md).

## Current stage

The project is in the technical-validation stage. The first release gate is to
prove that Apache POI can read, save, reopen, and selectively update compatible
`.xls` and `.xlsx` workbooks without changing protected workbook content.

No local Java, Maven, Node.js, or Docker installation is required. The
technical validation is designed to run in GitHub Actions.

## Remote validation

1. Publish this directory as a GitHub repository.
2. Open the repository's **Actions** page.
3. Run **Excel technical validation**, or push a change that affects the
   worker, workflow, template, or validation documents.
4. Treat a failed fidelity test as a release blocker.

The validation scope and remaining exit criteria are tracked in
[`docs/technical-validation.md`](docs/technical-validation.md).

