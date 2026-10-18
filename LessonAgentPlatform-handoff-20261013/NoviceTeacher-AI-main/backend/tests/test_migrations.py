from io import StringIO
from alembic.config import Config
from alembic import command
from sqlalchemy import inspect, MetaData, select
from datetime import datetime
from app.db import make_engine
from app.settings import settings
from pathlib import Path


def test_sqlite_migration_upgrade_and_schema(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, 'database_url', 'sqlite:///' + str(tmp_path / 'migration.db'))
    cfg = Config(str(Path(__file__).resolve().parents[1] / 'alembic.ini'))
    command.upgrade(cfg, 'head')
    command.upgrade(cfg, 'head')
    engine = make_engine(settings.database_url)
    assert {'sessions', 'decisions', 'generation_records', 'interaction_events'} <= set(inspect(engine).get_table_names())
    assert len(inspect(engine).get_check_constraints('sessions')) == 1
    assert len(inspect(engine).get_check_constraints('rounds')) == 2
    assert len(inspect(engine).get_check_constraints('decisions')) == 1
    assert {'structured_content', 'content_format', 'format_provenance', 'structure_warnings'} <= {
        column['name'] for column in inspect(engine).get_columns('lesson_plan_versions')}
    assert next(column for column in inspect(engine).get_columns('suggestions') if column['name'] == 'edits')['nullable']
    command.check(cfg)
    engine.dispose()


def test_postgresql_offline_migration_uses_jsonb(monkeypatch):
    monkeypatch.setattr(settings, 'database_url', 'postgresql+psycopg://test:test@localhost/test')
    output = StringIO()
    cfg = Config(str(Path(__file__).resolve().parents[1] / 'alembic.ini'), output_buffer=output)
    command.upgrade(cfg, 'head', sql=True)
    sql = output.getvalue()
    assert 'JSONB' in sql
    assert 'TIMESTAMP WITH TIME ZONE' in sql
    assert 'CREATE TABLE section_versions' in sql


def test_upgrade_keeps_existing_version_bytes_and_does_not_invent_format(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, 'database_url', 'sqlite:///' + str(tmp_path / 'legacy-history.db'))
    cfg = Config(str(Path(__file__).resolve().parents[1] / 'alembic.ini'))
    command.upgrade(cfg, '20261014_claims')
    engine = make_engine(settings.database_url)
    old = MetaData()
    old.reflect(engine)
    moment = datetime(2026, 10, 14, 1, 2, 3)
    content = '旧版正文\n' + r'Folder | C:\new | 字面量\n'
    with engine.begin() as connection:
        connection.execute(old.tables['users'].insert().values(id='u', created_at=moment,
            username='migration-fixture', role='user', updated_at=moment))
        connection.execute(old.tables['participants'].insert().values(id='p', user_id='u', created_at=moment))
        connection.execute(old.tables['sessions'].insert().values(id='s', user_id='u', participant_id='p',
            created_at=moment, updated_at=moment, request_key='key', input_hash='0' * 64,
            status='ACTIVE', current_round_number=1, config_snapshot={}, lesson_metadata={}))
        connection.execute(old.tables['lesson_plans'].insert().values(id='l', user_id='u', session_id='s',
            created_at=moment, original_content=content, current_content=content))
        connection.execute(old.tables['lesson_plan_versions'].insert().values(id='v', user_id='u', lesson_plan_id='l',
            created_at=moment, round_number=0, content=content))
    engine.dispose()
    command.upgrade(cfg, 'head')
    command.upgrade(cfg, 'head')
    engine = make_engine(settings.database_url)
    upgraded = MetaData()
    upgraded.reflect(engine)
    with engine.connect() as connection:
        row = connection.execute(select(upgraded.tables['lesson_plan_versions'])).mappings().one()
        assert row['id'] == 'v' and row['round_number'] == 0
        assert row['content'].encode('utf-8') == content.encode('utf-8')
        assert all(row[field] is None for field in (
            'structured_content', 'content_format', 'format_provenance', 'structure_warnings'))
    command.check(cfg)
    engine.dispose()
