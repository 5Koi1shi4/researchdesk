"""Authorized retrieval. Only internal services may supply the Java-frozen scope."""
from .search import retrieve
from .types import Evidence

__all__ = ['retrieve', 'Evidence']
