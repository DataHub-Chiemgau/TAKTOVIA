# IaC-Gerüst (WP-M0-11)

Anbieterneutrales Terraform-Gerüst für die vier Umgebungen `development`, `staging`, `performance`, `production`
(Kap. 01 §1.8). **Nicht angewendet; `terraform plan/validate` wurde nicht ausgeführt**, weil weder Terraform noch
OpenTofu in der Entwicklungsumgebung installiert ist. Geprüft wird nur statisch durch `infra/check.py`.

- `modules/environment`: gemeinsames Modul; Pflichtparameter ohne Default (fehlt einer, bricht die Validierung ab).
- `envs/<umgebung>`: Aufrufer je Umgebung mit eigener Geheimnis-, Identitäts- und PSP-Domäne.
- Hostingentscheidung (D-05) nur über `hosting_provider`/`hosting_region` (Default `unset`); anbieterspezifische
  Ressourcen kommen erst nach D-05 hinter diese Parameter.
- Migrationszugang und Laufzeitzugang sind getrennte Parameter; das Modul lehnt gleiche Namen ab.
- D-02: das Gerüst geht vom RLS-Default aus (keine schemaweise Isolation vorgesehen).
- Geheimnisse: nie im Repository (`terraform.tfvars` ist ignoriert, enthält ohnehin nur Namen von Secret-Stores).
  Konzept: siehe `../deploy/geheimnisse.md`.
- Umgebungsmatrix: `../deploy/umgebungsmatrix.csv`.
- Namenskonvention: `taktovia-<umgebung>-<komponente>`.
