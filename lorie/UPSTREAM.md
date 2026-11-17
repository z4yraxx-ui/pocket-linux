# Origem deste módulo

Este diretório é uma cópia do módulo `lorie` do **Termux:X11**
(https://github.com/termux/termux-x11, licença GPL-3.0), no commit
`a00942e81d949070e63fac268f37226838a13839`. O diretório `lorie-stub` é o
`shell-loader/stub` do mesmo repositório (declarações das APIs ocultas do Android
usadas só na compilação).

As bibliotecas de terceiros (xserver, pixman, libx11 etc.) são submódulos do git em
`lorie/src/main/cpp/`, nos mesmos commits do Termux:X11. Para baixá-los:

    git submodule update --init --recursive

## O que foi mudado em relação ao original

- `lorie/build.gradle`: simplificado para este projeto (sem descobrir o app host, sem o
  módulo `shell-loader`; `APPLICATION_ID` fixo em `com.pocketlinux.app`; só ABI `arm64-v8a`;
  `compileSdk 35`, `minSdk 26`).
- `lorie/src/main/java/com/termux/x11/CmdEntryPoint.java`: se a variável de ambiente
  `POCKETLINUX_XLORIE` existir, carrega o `libXlorie.so` desse caminho (o APK do PocketLinux
  guarda as bibliotecas compactadas e extraídas no disco, então não dá para carregar de dentro do APK).
- `lorie-stub/build.gradle`: `compileSdk 35` e versão do `androidx.annotation`.

O restante é o código original do Termux:X11.
