# Подготовка и публикация 1.1.0

Репозиторий: https://github.com/artemalo/plugin-openide-class-diagrams

## Подготовка файлов

Из корня проекта запустите PowerShell:

```powershell
.\package-release.ps1
```

Скрипт собирает плагин, запускает проверки ядра и проверку в отдельном профиле OpenIDE,
затем готовит в `dist` установочный ZIP, архив исходников и SHA-256.
Он не выполняет Git-команды и не обращается к GitHub. Для другой установки используйте
`-IdeHome 'путь к OpenIDE'`.

Архивы 1.0.0 остаются в `dist`; сборка 1.1.0 их не меняет.
В репозиторий идут исходники, скрипты, документация и `.github`.
`build`, `sandbox`, `dist`, настройки IDE и локальные логи исключены через `.gitignore`.
Дистрибутив IDE и её SDK в архивы релиза не входят.

## Что опубликовать

После собственного коммита итоговых файлов и отправки его на GitHub создайте тег
**v1.1.0** именно на этом коммите. Затем в **Releases → Draft a new release**:

1. Выберите тег `v1.1.0`.
2. Заголовок: **Class Diagrams for OpenIDE 1.1.0**.
3. Описание: содержимое [docs/releases/v1.1.0.md](docs/releases/v1.1.0.md).
4. Прикрепите `dist/class-diagrams-1.1.0.zip`, `dist/class-diagrams-1.1.0-sources.zip`
   и `dist/SHA256SUMS-1.1.0.txt`.
5. Сохраните черновик, проверьте вложения и нажмите **Publish release**.

Название существующего коммита `init V1.0` не гарантирует, что его содержимое совпадает
с сохранённым пакетом 1.0.0: репозиторий создавался во время работы над стрелками.
Не используйте этот коммит как основу тега 1.0.0 без отдельной сверки с архивом исходников.

## Вариант через GitHub CLI

Когда итоговый коммит **и тег v1.1.0 уже отправлены** на GitHub, следующая команда
создаёт только черновик релиза. Команда приведена для самостоятельного запуска:

```powershell
gh release create v1.1.0 `
  'dist/class-diagrams-1.1.0.zip' `
  'dist/class-diagrams-1.1.0-sources.zip' `
  'dist/SHA256SUMS-1.1.0.txt' `
  --repo artemalo/plugin-openide-class-diagrams `
  --verify-tag `
  --draft `
  --title 'Class Diagrams for OpenIDE 1.1.0' `
  --notes-file 'docs/releases/v1.1.0.md'
```

`--verify-tag` прерывает команду, если тега ещё нет на сервере; `--draft` сохраняет
релиз черновиком. [Справка GitHub CLI](https://cli.github.com/manual/gh_release_create).

## Автоматические проверки

GitHub Actions запускает `test-core.ps1` на Windows и Linux, с JDK 21 и 25.
Проверяются маршрутизация, жесты мыши, раскладка, фильтры и экспорт. Эти проверки
не требуют SDK IDE и не подтверждают загрузку плагина внутри OpenIDE.
Полная сборка и `integration-test.ps1` выполняются локально с установленным OpenIDE.
Workflow не создаёт коммиты, теги или релизы.
