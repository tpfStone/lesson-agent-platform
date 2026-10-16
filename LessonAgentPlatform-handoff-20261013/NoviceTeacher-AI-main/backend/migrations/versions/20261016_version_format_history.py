"""Persist the source of each F4 version; leave unknown legacy text unchanged."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = '20261016_version_format'
down_revision = '20261014_claims'
branch_labels = None
depends_on = None


def upgrade():
    json_type = sa.JSON().with_variant(JSONB(), 'postgresql')
    op.add_column('lesson_plan_versions', sa.Column('structured_content', json_type, nullable=True))
    op.add_column('lesson_plan_versions', sa.Column('content_format', sa.String(40), nullable=True))
    op.add_column('lesson_plan_versions', sa.Column('format_provenance', sa.String(40), nullable=True))
    op.add_column('lesson_plan_versions', sa.Column('structure_warnings', json_type, nullable=True))


def downgrade():
    raise RuntimeError('Version history rollback requires a verified backup; retain the compatible reader.')
