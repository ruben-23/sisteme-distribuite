# LAN Torrent (BitTorrent Client)

O aplicație de tip Peer-to-Peer (P2P) inspirată de protocolul BitTorrent, care permite partajarea și descărcarea de fișiere într-o rețea locală (LAN). Aplicația utilizează un mecanism de identificare de tip *multicast discovery* pentru găsirea nodurilor și un sistem bazat pe fragmente (*pieces*) pentru transferul eficient al datelor.

## Caracteristici principale
- **Partajare de fișiere:** Permite transformarea oricărui fișier local într-un torrent și partajarea acestuia în rețea.
- **Descărcare distribuită:** Descarcă simultan fragmente din fișier de la mai multe noduri (*peers*) din rețea.
- **Descoperire P2P:** Folosește un mecanism de descoperire locală (`LocalDiscovery`) pentru a găsi alți utilizatori din rețea care dețin fișierele dorite.
- **Interfață CLI (Command Line Interface):** O interfață simplă și interactivă în terminal.
- **Stare rețea:** Permite vizualizarea în timp real a tuturor nodurilor (*peers*) active și a fișierelor partajate în rețea.

## Arhitectură și Componente
- **Core / Protocol:** Gestionează formatul fișierelor `.torrent` și logica protocolului BitTorrent (*Handshake*, *Bitfield*, *Request*, *Piece*).
- **Discovery multicast (LAN):** Facilitează găsirea informațiilor și a nodurilor în rețea prin mesaje multicast locale.
- **Peer / Network:** Gestionează conexiunile directe între noduri (Client/Server) prin intermediul socket-urilor.
- **Utilitare:** Hashing (SHA-1), parsare și encodare Bencode (specific protocolului BitTorrent).

## Cerințe de sistem
- **Java:** JDK 17 sau o versiune mai recentă.
- **Maven:** Instalat pe sistem (opțional, dar recomandat pentru managementul build-ului și al dependențelor – deși proiectul nu folosește neapărat librării externe, respectă structura unui proiect Maven).
- **Rețea:** O rețea locală (LAN) funcțională, pentru testarea cu mai multe noduri pe mașini diferite sau pe aceeași mașină.

## Cum se construiește (Build)

Dacă folosiți Maven, puteți compila proiectul rulând următoarea comandă în directorul rădăcină al acestuia:

```bash
mvn clean package
```
Alternativ, puteți deschide proiectul în IDE-ul dumneavoastră preferat (ex: IntelliJ IDEA, Eclipse) care are suport pentru Maven, și să inițiați procesul de *build* direct din interfața acestuia.

## Cum se rulează

**Din IDE:** Puteți rula direct metoda `main` din clasa `org.example.Main`.

**Folosind terminalul** (din directorul rădăcină al proiectului, după compilarea cu `mvn package` sau `mvn compile`):
```bash
java -cp target/classes org.example.Main
```

### Utilizarea aplicației
La pornirea aplicației, se va aloca un port aleatoriu între 6881 și 6889, iar în consolă se va afișa meniul principal:

```text
=== LAN Torrent ===
1. Share a file
2. Download from torrent
3. Exit
4. Show all active peers and shared files
```

- **Opțiunea 1 (Share a file):** Sistemul vă va cere calea către fișierul de pe disc pe care doriți să-l partajați, precum și un director de destinație/descărcare. Aplicația va genera automat un fișier `.torrent` (ex: `fisier.txt.torrent`), pe care îl veți putea oferi altor utilizatori pentru ca aceștia să vă poată descărca fișierul.
- **Opțiunea 2 (Download from torrent):** Sistemul vă va cere calea către fișierul `.torrent` obținut anterior și calea/directorul unde doriți să fie salvat fișierul descărcat. Aplicația va interoga automat mecanismul de *Discovery* pentru a căuta nodurile care dețin acel fișier și va iniția procesul de descărcare distribuită.
- **Opțiunea 3 (Exit):** Oprește nodul local (închide serverul) și închide corect aplicația.
- **Opțiunea 4 (Show network status):** Afișează o listă a tuturor nodurilor cunoscute momentan prin mecanismul de *Discovery* și a fișierelor care sunt active și partajate în rețea.

## Note suplimentare
- Fișierele `.torrent` conțin metadatele necesare pentru identificarea unică a datelor și verificarea integrității acestora la descărcare, folosind funcții criptografice (hash).
- Sistemul utilizează protocoale de comunicare bazate pe mesaje specifice (`Request`, `Piece`, `Handshake`) pentru transferul propriu-zis al fragmentelor de date (*pieces*) între `PeerClient` și `PeerServer`.