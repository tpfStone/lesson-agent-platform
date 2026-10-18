"""Store node-level suggestions without rewriting existing suggestions/history."""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects.postgresql import JSONB

revision = '20261018_structured_edits'
down_revision = '20261016_version_format'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('suggestions', sa.Column('edits', sa.JSON().with_variant(JSONB(), 'postgresql'), nullable=True))


def downgrade():
    raise RuntimeError('Retain structured edit history; rollback requires a verified backup.')
