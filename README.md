# PocketLinux

Seu ambiente Linux no Android: Debian ARM64 + LXQt rodando dentro do app, sem root, com instalação e execução integradas (sem terminal externo, sem adb).

## Estado desta versão (0.6.0, fase 3)

- ✅ O botão **Instalar Debian** baixa o sistema pronto (Release do seu GitHub), confere o SHA-256 e extrai no armazenamento privado do app. O download continua de onde parou se cair.
- ✅ O botão **Iniciar Debian** abre um shell do Debian (via PRoot) dentro do app, num terminal de texto. `sudo apt update && sudo apt upgrade` funciona.
- ✅ **Fase 2.1:** o Debian continua rodando com o app fechado (serviço em primeiro plano com notificação e botão "Encerrar"). Na primeira vez o app pede a isenção de otimização de bateria.
- ✅ **Fase 2.2:** botão "Atualizar pacotes": busca as atualizações, mostra quantos pacotes são e só instala depois de você confirmar.
- ✅ **Fase 2.3:** pasta `~/Android` no Debian. Com a permissão de armazenamento ela mostra o armazenamento do celular; sem ela, é uma pasta própria do app.
- ✅ **Fase 2.5:** tela de Diagnóstico (memória, processos, espaço, versão do Debian) com botão "Copiar relatório".
- ✅ **Fase 2.4:** terminal com PTY real (código nativo próprio em `app/src/main/cpp/pty.c`) e emulador de terminal próprio: `nano`, `top`, `Ctrl+C`, cores, rolagem do histórico, colar, e uma barra com Esc, Tab, Ctrl, Alt e setas. Falta a seleção de texto por toque (hoje há "Copiar" tudo e "Colar").
- ✅ **Fase 3:** desktop LXQt na tela, com o servidor X do Termux:X11 embutido no app (pasta `lorie/`, GPL-3.0). Sem VNC: os programas do Debian falam direto com o servidor X por um socket local. O botão **Abrir desktop** sobe o servidor, abre a tela e inicia o LXQt como usuário `pocket`.
- ⏳ **Fase 4:** polimento e botão único "Abrir Debian".

## Como compilar (GitHub Actions)

O repositório precisa ser **público** (o app baixa o Debian sem login e o runner ARM64 só é gratuito em repositórios públicos).

1. **Monte o Debian (uma vez, e de novo na fase 3 para trazer os pacotes do teclado/fontes do X):** aba **Actions** → **Build Debian rootfs** → **Run workflow**. Demora um tempo e publica o arquivo na Release `rootfs-latest`. Repita quando quiser uma base mais nova.
2. **Compile o app:** o workflow **Build PocketLinux APK** gera o APK (artefato `PocketLinux-APK`). Ele já informa ao app de qual repositório baixar o Debian.
3. Instale o APK, abra o app e toque em **Instalar Debian** (use Wi-Fi).

O servidor X vem de submódulos do git (`git clone --recurse-submodules` ou `git submodule update --init --recursive`); o workflow já faz isso. Compilar a parte nativa leva alguns minutos.

Para compilar localmente: `gradle assembleDebug -ProotfsRepo=seu-usuario/PocketLinux` (JDK 17, Android SDK 35, NDK 29.0.14206865, CMake 3.22.1, python3, bison e patch).

## Como funciona

- **PRoot** (`libproot.so` + `libproot-loader.so`) é baixado no build e embutido no APK como biblioteca nativa, a única pasta de onde o Android deixa executar arquivos.
- **targetSdk 28** de propósito, como o Termux: a partir do 29 o Android proíbe executar arquivos da pasta de dados do app, e é lá que fica o Debian.
- **O Debian vem do Docker oficial `debian:<versão>`**, com os pacotes do repositório oficial `deb.debian.org`, montado pelo workflow e já com LXQt, `sudo`, locales e ajustes para o `apt` funcionar no PRoot.
- Dentro do Debian você é "root" simulado pelo PRoot. Comandos que dependem do kernel de verdade (montar discos, drivers) não funcionam.

## Limitações conhecidas desta fase

- O terminal é de texto simples, sem terminal real (PTY): programas de tela cheia (`nano`, `vim`, `top`) não funcionam direito, e `Ctrl+C` não existe ainda.
- Se você sair do app durante a instalação, o Android pode encerrá-lo. O download continua de onde parou ao tocar em Instalar de novo.
- O Android 12+ limita processos filhos de apps ("phantom process killer"), o que pode derrubar o Debian com muitos programas abertos. Será tratado nas próximas fases.

## Licenças de terceiros

- Este projeto: GPL-3.0 (arquivo LICENSE).
- Termux:X11 (servidor X, módulo `lorie`): GPL-3.0, https://github.com/termux/termux-x11. Ver `lorie/UPSTREAM.md`.
- Bibliotecas do servidor X (xserver, pixman, libx11 etc.): licenças de cada projeto, em `lorie/src/main/cpp/`.
- PRoot: GPL-2.0+ (https://github.com/proot-me/proot). Binários de https://github.com/green-green-avk/build-proot-android (scripts MIT; talloc LGPL).
- Apache Commons Compress: Apache-2.0.
- Debian e os pacotes instalados têm as licenças de cada projeto.
