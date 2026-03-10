# Extract killed/survived counts from a stryker4s mutation-testing-report.json
{
  killed:   [.files[].mutants[] | select(.status == "Killed")]   | length,
  survived: [.files[].mutants[] | select(.status == "Survived")] | length
}
