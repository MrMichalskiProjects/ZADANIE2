# Zbudowanie obrazu
docker build -t apka1:latest .

# Uruchomienie kontenera
docker run -d --name apka1 -p 8080:8080 apka1:latest

# Logi z uruchomienia apki
docker logs apka1
- `-f` - follow; daje logi na zywo
- `t` - time; daje timestampy
- `--tail n` - n logow z konca

# Liczba warstw i rozmiar obrazu
docker image inspect apka1:latest --format '{{.Size}}' | numfmt --to=iec # rozmiar
docker image inspect apka1:latest --format '{{len .RootFS.Layers}}' # warstwy, odfiltrowuje te z metadanymi, co maja 0B

# Odpalenie z registry githuba (zbidowane przez GHA)
sudo docker run --rm -p 8080:8080 ghcr.io/mrmichalskiprojects/zadanie2:latest
