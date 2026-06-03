CI/CD - budowa, skan CVE i publikacja obrazu

Pipeline: .github/workflows/build-publish.yml

Co robi: buduje wieloarchitekturowy obraz (amd64 + arm64) z Dockerfile,
skanuje go pod katem podatnosci i publikuje do publicznego repo na GitHub
Packages (ghcr.io/<owner>/apka1) - ale tylko jesli skan nie wykryje
podatnosci CRITICAL ani HIGH.

Przebieg
1. checkout - pobranie repo
2. setup-qemu - emulacja arm64 na runnerze amd64
3. setup-buildx - builder docker-container (potrzebny do cache typu registry)
4. login do ghcr.io - push obrazu (GITHUB_TOKEN)
5. login do docker.io - odczyt/zapis cache
6. metadata-action - wyliczenie tagow i etykiet
7. build amd64 (load) - obraz do skanu w lokalnym demonie + zapis cache mode=max
8. Trivy (severity CRITICAL,HIGH, exit-code 1) - bramka CVE, blad zatrzymuje pipeline
9. build+push amd64+arm64 - publikacja do ghcr.io (tylko gdy krok 8 przeszedl)

Wiele architektur
QEMU rejestruje emulator, buildx dostaje platforms amd64,arm64 i publikuje
pojedynczy manifest list. W Dockerfile loader musl kopiowany jest globem
ld-musl-*.so.1, bo nazwa zalezy od platformy.

Cache
Trzymany w osobnym, publicznym repo na Docker Hub (apka1-cache:buildcache),
typ registry, mode=max - zapisuje warstwy wszystkich etapow multi-stage.
Najwiekszy zysk przy wolnej budowie pod QEMU.

Skan CVE
Wybrano Trivy zamiast Docker Scout: jedna akcja, bez dodatkowego konta,
open-source, latwa bramka (severity + exit-code 1 przerywa job).
Skanujemy obraz amd64 zaladowany do lokalnego demona (load nie obsluguje
multi-arch). amd64 jest reprezentatywny - obie architektury maja te same
pakiety i ten sam apka1.jar. Dzieki cache krok 9 buduje realnie tylko arm64.

Tagowanie obrazu
- sha-<full-sha>  - kazdy build, niemutowalny, wiaze obraz z commitem
- latest          - tylko main, wskaznik ostatniej dobrej wersji
- <branch>        - push na galaz, podglad buildu
- 1.2.3 / 1.2     - tag gita v1.2.3, wydania semver
Glownym, zawsze obecnym tagiem jest niemutowalny sha-<commit>; latest jest
mutowalny i nie nadaje sie do powtarzalnych wdrozen (zalecenia Kubernetes,
OCI, semver).

Tag cache
Jeden staly tag :buildcache w osobnym repo - backend registry nadpisuje te
sama referencje przy kazdym buildzie, wiec zawsze jest aktualny cache.

Wymagana konfiguracja repo (Settings -> Secrets and variables -> Actions)
- Variable DOCKERHUB_USERNAME - login Docker Hub
- Secret   DOCKERHUB_TOKEN    - token Docker Hub (Read & Write)
GITHUB_TOKEN jest wbudowany. Po pierwszej publikacji ustaw pakiet jako Public.
Repo cache na Docker Hub tez ma byc publiczne.

Uruchomienie
- automatycznie: push na main lub tag v*.*.*
- recznie: Actions -> build-publish -> Run workflow
- pull_request: build + skan CVE bez publikacji
