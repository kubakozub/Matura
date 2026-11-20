# Kopia zapasowa pracy w toku

Ten katalog jest tymczasowy i zniknie przed wydaniem. Zawiera niedokonczona prace zespolow z osobnych kopii roboczych,
zapisana jako latki (git diff --binary). Kazda latka naklada sie na podany commit bazowy:

    git checkout <commit bazowy> && git apply --index .wip-backup/<nazwa>.patch

| zespol | galaz | commit bazowy | plik |
|---|---|---|---|
| petla | wt/petla-nauki | e74587f6e7f5b245a1e801c0948ba99f7c1a4eae | petla.patch |
| dopasowanie | wt/dopasowanie | 1b2d6af318a40bf5c5244ae0356e82cc3d4b4fb7 | dopasowanie.patch |
