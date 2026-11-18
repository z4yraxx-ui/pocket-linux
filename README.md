# PocketLinux

Um ambiente Linux no Android: Debian ARM64 + LXQt rodando dentro do app, sem root, com instalação e execução integradas (sem terminal externo, sem adb).

## Estado desta versão (0.5.0, fase 2)

- ✅ O botão **Instalar Debian** baixa o sistema pronto (Release do seu GitHub), confere o SHA-256 e extrai no armazenamento privado do app. O download continua de onde parou se cair.
  
- ✅ O botão **Iniciar Debian** abre um shell do Debian (via PRoot) dentro do app, num terminal de texto. `sudo apt update && sudo apt upgrade` funciona.
  
- ✅ **Fase 2.1:** o Debian continua rodando com o app fechado (serviço em primeiro plano com notificação e botão "Encerrar"). Na primeira vez o app pede a isenção de otimização de bateria.
  
- ✅ **Fase 2.2:** botão "Atualizar pacotes": busca as atualizações, mostra quantos pacotes são e só instala depois de você confirmar.
  
- ✅ **Fase 2.3:** pasta `~/Android` no Debian. Com a permissão de armazenamento ela mostra o armazenamento do celular; sem ela, é uma pasta própria do app.
  
- ✅ **Fase 2.5:** tela de Diagnóstico (memória, processos, espaço, versão do Debian) com botão "Copiar relatório".
  
- ✅ **Fase 2.4:** terminal com PTY real (código nativo próprio em `app/src/main/cpp/pty.c`) e emulador de terminal próprio: `nano`, `top`, `Ctrl+C`, cores, rolagem do histórico, colar, e uma barra com Esc, Tab, Ctrl, Alt e setas. Falta a seleção de texto por toque (hoje há "Copiar" tudo e "Colar").
- 
- ✅️ **Fase 3:** servidor X11 embutido e desktop LXQt na tela funcionando como deveria, muito bem parabéns 🫪, além de que pode ter instabilidades pois todo projeto foi pensando num galaxy intermediário (Android 17 one ui 9.0), todo o projeto foi pensado para meu uso individual para dar praticidade ao fluxo de trabalho no Android (precisava disso urgente e foi feito em cerca de duas madrugadas 🫪) sem a ideia de tornar um produto público real, por isso pode não funcionar em outros dispositivos (exige a instalação separada do WM para o lxqt)
  
- ⏳ **Fase 4:** polimento e botão único "Abrir Debian".

## Licenças de terceiros

- Este projeto: GPL-3.0 (arquivo LICENSE).
- PRoot: GPL-2.0+ (https://github.com/proot-me/proot). Binários de https://github.com/green-green-avk/build-proot-android (scripts MIT; talloc LGPL).
- Apache Commons Compress: Apache-2.0.
- Debian e os pacotes instalados têm as licenças de cada projeto.

- 
