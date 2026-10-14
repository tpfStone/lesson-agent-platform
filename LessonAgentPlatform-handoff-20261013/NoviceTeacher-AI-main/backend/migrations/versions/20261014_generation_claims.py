"""Short generation claims replace locks held during remote model calls."""
from alembic import op
import sqlalchemy as sa
revision = '20261014_claims'
down_revision = '20261014_structure'
branch_labels = None
depends_on = None
def upgrade():
    op.add_column('section_reviews', sa.Column('generation_claim', sa.String(36), nullable=True))
    op.add_column('section_reviews', sa.Column('claimed_at', sa.DateTime(timezone=True), nullable=True))
def downgrade():
    raise RuntimeError('Keep compatible claims reader; restore only from a verified backup.')
