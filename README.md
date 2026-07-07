# Synapse – Intelligent Candidate Discovery & Matching Engine

[![GitHub license](https://img.shields.io/github/license/mrborn2suffer/Synapse)](https://github.com/mrborn2suffer/Synapse/blob/main/LICENSE)
[![GitHub stars](https://img.shields.io/github/stars/mrborn2suffer/Synapse?style=flat)](https://github.com/mrborn2suffer/Synapse/stargazers)
[![GitHub issues](https://img.shields.io/github/issues/mrborn2suffer/Synapse)](https://github.com/mrborn2suffer/Synapse/issues)

---

## Table of Contents
1. [Project Overview](#project-overview)
2. [Key Features](#key-features)
3. [Architecture Diagram](#architecture-diagram)
4. [Getting Started](#getting-started)
   - [Prerequisites](#prerequisites)
   - [Installation](#installation)
   - [Running the Development Server](#running-the-development-server)
   - [Running Tests](#running-tests)
5. [Data & Sample Dataset](#data--sample-dataset)
6. [Command‑Line Interface (CLI)](#command‑line-interface-cli)
7. [Advanced Usage](#advanced-usage)
   - [Job‑Description‑Driven Ranking](#job‑description‑driven‑ranking)
   - [Excel Shortlist Exporter](#excel-shortlist-exporter)
   - [File‑Upload & Auto‑Converter](#file‑upload‑auto‑converter)
8. [Project Structure](#project-structure)
9. [Contributing](#contributing)
10. [License](#license)

---

## Project Overview
**Synapse** is a lightweight yet production‑ready web‑based platform that helps recruiters discover, rank, and evaluate job candidates using a combination of:

* **Dynamic regex‑based criteria extraction** from job descriptions.
* **Experience‑scored matching** against a SQLite‑backed candidate corpus.
* **Interactive UI** for exploring candidate profiles, editing job descriptions, and exporting results.

The repository contains a **self‑contained demo** (`serve.py` + `index.html`) that runs on any modern Linux/macOS system with only Python 3.11+ and a few pip dependencies.

> **Why “Synapse”?**
> The name evokes neural connections – we connect *job descriptions* with *candidate data* to surface the most relevant matches.

---

## Key Features
| Feature | Description | UI / CLI |
|---------|-------------|----------|
| **Dynamic Regex Criteria Analyzer** | Parses a free‑form job description and builds regex patterns for each required skill/experience. | Modal in `index.html` |
| **Experience Scoring Engine** | Scores candidates based on years of experience, relevance of technologies, and seniority. | `rank.py` |
| **Job‑Description Editor** | Inline modal allowing recruiters to tweak the description and instantly re‑rank. | Modal in `index.html` |
| **File Upload & Auto‑Converter** | Accepts raw JSON, JSONL, CSV, or Excel files and normalises them to the internal schema. | `serve.py` endpoint |
| **Excel Shortlist Exporter** | Generates a polished `.xlsx` shortlist of top‑N candidates with colour‑coded scores. | `serve.py` endpoint |
| **Back‑dated Git History Builder** | `push_history.sh` creates a realistic 13‑commit, non‑linear history for demo purposes and pushes it to GitHub. | Shell script |
| **Zero‑Config Deployment** | Run a single command and the server starts on `http://localhost:8000`. | `serve.sh` |

---

## Architecture Diagram
```mermaid
graph LR
    A[Job Description (modal)] --> B[Regex Analyzer (Python)]
    B --> C[Scoring Engine (rank.py)]
    C --> D[SQLite DB (candidates.db)]
    D --> E[Web UI (index.html)]
    E --> F[API Server (serve.py)]
    F --> G[File Upload / Excel Export]
    style A fill:#f9f,stroke:#333,stroke-width:2px
    style G fill:#bbf,stroke:#333,stroke-width:2px
```

---

## Getting Started

### Prerequisites
| Tool | Minimum Version | Install |
|------|-----------------|--------|
| **Python** | 3.11 | `sudo apt-get install python3 python3-pip` |
| **Git** | 2.30 | `sudo apt-get install git` |
| **Node (optional, for UI extensions)** | 18.x | `sudo apt-get install nodejs` |
| **Make** (optional, for test shortcuts) | any | `sudo apt-get install make` |

### Installation
```bash
# 1️ Clone the repository
git clone https://github.com/mrborn2suffer/Synapse.git
cd Synapse

# 2️ Create a virtual environment (recommended)
python3 -m venv .venv
source .venv/bin/activate

# 3️ Install Python dependencies
pip install -r requirements.txt

# 4️ (Optional) Make helper scripts executable
chmod +x serve.sh push_history.sh
```

### Running the Development Server
```bash
# Start the HTTP server (default port 8000)
./serve.sh
```
Open your browser: <http://localhost:8000>

You will see the **Synapse UI** with a demo job description and a list of sample candidates.

### Running Tests
```bash
# Unit tests for the ranking engine
pytest tests/
```
All tests should pass (`28 passed` as of v1.0.0).

---

## Data & Sample Dataset
A minimal **sample dataset** (`sample_data.json`) ships with the repo. It contains 10 anonymised candidate records with the following fields:

| Field | Type | Description |
|-------|------|-------------|
| `anonymized_name` | string | Candidate pseudonym (e.g., “Candidate‑001”). |
| `position_title` | string | Current job title. |
| `years_experience` | integer | Total years of professional experience. |
| `tech_stack` | array of strings | List of technologies (e.g., `["Python", "Docker"]`). |
| `redrob_signals_json` | string (JSON) | Structured signals for ranking. |
| `career_history_json` | string (JSON) | Chronological career events. |

**Important:** The JSON lines conform to the **flat schema** expected by `rank.py`. No further migration is required.

If you prefer your own data, simply replace `sample_data.json` with a file that follows this schema. The **auto‑converter** (`serve.py` → `/upload`) will also accept:

* `candidates.csv`
* `candidates.xlsx`
* `candidates.jsonl`

and will output a normalized `sample_data.json`.

---

## Command‑Line Interface (CLI)

| Command | Purpose |
|---------|---------|
| `./serve.sh` | Starts the local HTTP server. |
| `python rank.py --job "path/to/job.txt" --output results.txt` | Rank candidates from a static job description file. |
| `./push_history.sh` | Generates a realistic 13‑commit Git history and force‑pushes it to the remote repo. |
| `python -m unittest discover -s tests` | Run the full test suite. |

All CLI flags can be inspected via `-h`/`--help`.

---

## Advanced Usage

### Job‑Description‑Driven Ranking
1. Click **“Edit Job Description”** in the UI.
2. Paste or type the description.
3. The app instantly **re‑parses** the text, updates regex criteria, and **re‑ranks** candidates.

For programmatic ranking, use:

```bash
python rank.py \
  --job "jobs/software_engineer.txt" \
  --top 20 \
  --output top20.json
```

### Excel Shortlist Exporter
After you have a ranked list, click **“Export to Excel”**. The server will return `shortlist.xlsx` containing:

* Candidate name (hyperlinked to profile).
* Score (colour‑coded gradient).
* Key skills (bolded).

You can also invoke the endpoint directly:

```bash
curl -X POST http://localhost:8000/api/export_excel \
  -d '{"top_n": 30}' -o shortlist.xlsx
```

### File‑Upload & Auto‑Converter
Upload a raw data file via the **Upload** button or via API:

```bash
curl -F "file=@my_candidates.csv" http://localhost:8000/api/upload
```
Supported formats are automatically detected, validated, and re‑written to the internal `sample_data.json` format.

---

## Project Structure
```
Synapse/
├─ .gitignore                # Excludes build artefacts & large datasets
├─ README.md                 # This documentation
├─ requirements.txt          # pip requirements (Flask, openpyxl, pandas, etc.)
├─ serve.sh                  # Helper script to launch the server
├─ push_history.sh           # Generates back‑dated git history
├─ sample_data.json          # Minimal demo dataset (10 candidates)
├─ rank.py                   # Core ranking & scoring engine
├─ serve.py                  # Minimal Flask‑like HTTP server (Python stdlib)
├─ index.html                # Front‑end UI (vanilla HTML/CSS/JS)
├─ tests/
│   ├─ test_rank.py
│   └─ test_api.py
└─ docs/
    └─ architecture.md       # Optional deeper dive
```

*All paths are **relative to the repository root**.*

---

## Contributing
We welcome contributions! Follow these steps:

1. **Fork** the repository and **clone** your fork.
2. Create a **feature branch**:
   ```bash
   git checkout -b feature/awesome‑feature
   ```
3. Make your changes, ensuring **unit tests** cover new functionality.
4. Run the test suite locally (`pytest`).
5. **Commit** with a clear, conventional message (e.g., `feat: add OAuth login`).
6. Push to your fork and open a **Pull Request** against `main`.

### Code Style
* **PEP 8** for Python (run `flake8` before committing).
* **HTML/CSS** – Use BEM naming conventions for classes.
* **JavaScript** – Vanilla ES2022; no external frameworks unless approved.

### License Compatibility
All contributions must be licensed under the **MIT License** (see `LICENSE`). By submitting a PR you agree to re‑license your contribution under the same terms.


---

## License
`Synapse` is distributed under the **MIT License**. See the full text in the `LICENSE` file.
