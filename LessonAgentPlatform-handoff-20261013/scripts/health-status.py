"""Classify configuration readiness without printing response contents or secrets."""
import json
import sys


def ready(body):
    return (isinstance(body, dict) and body.get('status') in ('up', 'UP', 'ok') and body.get('ready', True)
            and body.get('model_configured', True)
            and (body.get('provider') == 'mock' or body.get('llm_configured', True)))


if __name__ == '__main__':
    try: sys.exit(0 if ready(json.load(sys.stdin)) else 1)
    except (ValueError, TypeError): sys.exit(1)
