# Kopia zapasowa pracy w toku

Ten katalog jest tymczasowy i zniknie przed wydaniem. Zawiera niedokonczona prace zespolow z osobnych kopii roboczych,
zapisana jako latki (git diff --binary). Kazda latka naklada sie na podany commit bazowy:

    git checkout <commit bazowy> && git apply --index .wip-backup/<nazwa>.patch

| zespol | galaz | commit bazowy | plik |
|---|---|---|---|
| petla | wt/petla-nauki | e74587f6e7f5b245a1e801c0948ba99f7c1a4eae | petla.patch |
| dorobek | wt/dorobek | eb5ea0f811a48f5dd3764486a901efd923c5458a | dorobek.patch |
| dopasowanie | wt/dopasowanie | c4755d800286f56356f27177ca42c8db53c18a66 | dopasowanie.patch |
| zestawy | wt/zestawy | 46b7ef5946ead2d80805ba37f8045f2ca2d90daf | zestawy.patch |
