"""Add version-bound structure without changing legacy session sections."""
from alembic import op
import sqlalchemy as sa

revision = '20261014_structure'
down_revision = 'f3cc62ac42cf'
branch_labels = None
depends_on = None


def upgrade():
    op.add_column('lesson_plans', sa.Column('structured_content', sa.JSON(), nullable=True))
    op.add_column('lesson_plans', sa.Column('source_binding', sa.JSON(), nullable=True))
    op.add_column('lesson_plans', sa.Column('structure_warnings', sa.JSON(), nullable=True))
    op.add_column('lesson_plans', sa.Column('fallback_projection', sa.JSON(), nullable=True))
    op.add_column('sections', sa.Column('source_ref', sa.JSON(), nullable=True))


def downgrade():
    raise RuntimeError('Structure rollback requires a verified database backup; retain compatible reader.')
