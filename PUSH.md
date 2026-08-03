# Pushing Finto to GitHub

The repo is already initialised and committed locally. My sandbox has no `gh`
and no access to your GitHub credentials, so run these on your machine.

## 1. Move the files somewhere permanent

Everything is currently in this session's output folder. Move it to wherever
you keep projects:

```bash
mkdir -p ~/projects/finto
cp -R <output-folder>/. ~/projects/finto/
cd ~/projects/finto
```

The `.git` directory came along with the copy, so the commit history is intact.
Verify:

```bash
git log --oneline
git ls-files          # should list 19 files, no .db and no real statements
```

## 2. Create the private repo and push

```bash
gh repo create Finto --private --source=. --remote=origin --push
```

That single command creates the repo, wires up `origin`, and pushes `main`.

If you'd rather do it in steps:

```bash
gh repo create Finto --private
git remote add origin git@github.com:<your-username>/Finto.git
git branch -M main
git push -u origin main
```

## 3. Before you push — confirm nothing sensitive is staged

```bash
git ls-files | grep -Ei '\.(db|sqlite|pdf|xlsx)$'   # expect no output
git ls-files | grep -i 'accounts.yaml$'             # expect no output
```

Only `accounts.example.yaml` should appear — the real `accounts.yaml` is
gitignored, as are `*.db`, `inbox/`, `exports/`, and all statement formats.
The three fixture CSVs under `tests/fixtures/` are deliberately whitelisted;
they contain invented transactions only.

## 4. Set up and run

```bash
pip install -e ".[dev]"
pytest                                  # 26 tests should pass
python -m fin.cli init
cp accounts.example.yaml accounts.yaml  # edit with your real accounts
python -m fin.cli accounts load accounts.yaml
```

Keep `finto.db` local — it's gitignored and should stay that way.
