# Comande MondoQR — app Android (WebView + stampa nativa)

App leggera per il tablet del bar: mostra l'admin MondoQR a schermo intero e **stampa la comanda DIRETTA sulla termica** (niente Chrome, niente finestra di stampa, niente tap).

## Come funziona
- **WebView** a schermo intero che carica l'admin (`template.mondoqr.it/gestione`), schermo sempre acceso.
- **Ponte JS→nativo**: il web chiama `window.AndroidPrint.printTcp(markup, ip, porta, larghezza)`; l'app stampa via **ESC/POS (libreria DantSu)** sulla termica di rete `ip:9100`.
- Zero pop-up, zero dialog, zero tap. L'IP/porta/formato della stampante arrivano dalla dashboard (`config.comanda.printer`).

## Come ottenere l'APK (senza Android Studio)
1. Ogni push, **GitHub Actions** compila l'APK.
2. Vai su **Actions → ultimo run → Artifacts → `comande-mondoqr-apk`** e scarica lo zip.
3. Estrai l'`.apk`, mettilo sul tablet e installalo (attiva "installa da origini sconosciute").

## Sul tablet
- Termica in **rete (LAN/WiFi)** con **IP statico**, porta **9100**.
- In dashboard MondoQR imposta l'**IP della stampante** (config comanda).
- Apri l'app → login → tab Ordini. Le comande escono da sole.

## Stato / TODO
- v0: stampa **TCP di rete** (IP:9100). Bluetooth/USB = v2 (DantSu li supporta, servono permessi runtime).
- Lato web: l'admin deve chiamare `window.AndroidPrint.printTcp(...)` quando `window.AndroidPrint` esiste (build della comanda in markup DantSu). Da collegare in `comanda.ts`.
- Verifica finale: serve tablet + termica reale.

## Formato comanda (markup DantSu, prodotto dal web)
Allineamento `[L] [C] [R]`, dimensione `<font size='big'>OMBRELLONE 24</font>`, `<b>TOTALE</b>`, taglio automatico.
