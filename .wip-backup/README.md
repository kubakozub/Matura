# Kopia zapasowa pracy w toku

Ten katalog jest tymczasowy i zniknie przed wydaniem. Zawiera niedokonczona prace zespolow z osobnych kopii roboczych,
zapisana jako latki (git diff --binary). Kazda latka naklada sie na podany commit bazowy:

    git checkout <commit bazowy> && git apply --index .wip-backup/<nazwa>.patch

| zespol | galaz | commit bazowy | plik |
|---|---|---|---|
| dorobek | wt/dorobek | c931ea43a7dde0d830cf678629d2b129d75a8960 | dorobek.patch |
| dopasowanie | wt/dopasowanie | 1b2d6af318a40bf5c5244ae0356e82cc3d4b4fb7 | dopasowanie.patch |
| zestawy | wt/zestawy | 0aab7c56239b6f70487c7a0b4fd314a7f75b7c9d | zestawy.patch |
