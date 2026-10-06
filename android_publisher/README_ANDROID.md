# Shopee AI Publicador Android — V4 teste

Este app é o braço móvel do Shopee AI. O PC continua gerando produto, SEO, hashtags, copy, vídeo e link. O Android pega o primeiro item `ready_to_post`, baixa o vídeo e tenta executar o fluxo de publicação no aplicativo oficial da Shopee Brasil (`com.shopee.br`) usando um AccessibilityService.

## Licença do teste
- 30 dias a partir da primeira abertura.
- Nesta versão a licença é local e serve apenas para o teste técnico.
- A versão comercial deverá validar licença em servidor para permitir 1 mês, 3 meses, 6 meses, 1 ano ou vitalícia.

## Primeira configuração
1. PC e celular na mesma rede Wi‑Fi.
2. No PC, iniciar `INICIAR_TUDO.bat`.
3. Ver o `Network URL` do backend (porta 8000) e o código de pareamento mostrado no painel WEB.
4. No Android, informar o endereço do PC, por exemplo `http://192.168.15.9:8000`, e o código de pareamento.
5. Tocar em **SALVAR E TESTAR CONEXÃO**.
6. Tocar em **ATIVAR ACESSIBILIDADE** e habilitar `Shopee AI Publicador`.
7. Manter a conta da Shopee já logada.
8. Ativar **PUBLICAÇÃO AUTOMÁTICA**.

## Como o fluxo funciona
- O backend cria 10/20/30/40/50 anúncios e o Scheduler renderiza os vídeos.
- Quando um anúncio vira `ready_to_post`, o celular o reserva como `publishing`.
- O vídeo é salvo em `Filmes/ShopeeAI` no Android.
- O serviço abre a Shopee, tenta entrar em Shopee Video, selecionar o vídeo, vincular o produto, preencher copy/hashtags e publicar.
- Ao confirmar a saída da tela de publicação, o app marca `published` no backend e pega o próximo.
- Em falha, há no máximo 2 tentativas automáticas; depois o item fica `publish_error` para evitar postagens erradas em loop.

## Importante para o primeiro teste
A interface da Shopee muda entre versões e celulares. Esta V4 usa textos visíveis (`Shopee Video`, `Próximo`, `Adicionar Produto`, `Publicar` etc.) e heurísticas. Faça o primeiro lote com 1 publicação acompanhada visualmente. Se parar em alguma tela, envie uma captura dessa tela para ajustarmos o seletor exato daquela versão da Shopee.

## Xiaomi / HyperOS
Se o Android encerrar o publicador em segundo plano, coloque o app em **Sem restrições** na economia de bateria e permita inicialização automática.

## Compilar APK
O projeto é Android nativo (Kotlin), minSdk 29, targetSdk 35. Pode ser aberto no Android Studio e compilado sem emulador. Também existe `.github/workflows/build-android.yml` na raiz do pacote para compilar `app-debug.apk` pelo GitHub Actions.
