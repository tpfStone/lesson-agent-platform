# F2 lesson generation engine

This directory is the Python engine used by the Spring platform. Install the existing `requirements.txt` in its own virtual environment, then install the package with `python -m pip install -e .`. Start the formal API with `python -m paper4_pipeline.web_api` from this directory.

Run offline contracts with `python -m pytest tests -q`. These tests must not call real models. Real generation requires the deployment configuration described in the handoff root README.

The 2026-10-14 repair is based on a source snapshot without Git history. This README restores the packaging metadata's required readme file; it does not assert that missing historical test assets were recovered from the authoritative repository.
