#!/bin/sh
set -eu
psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /opt/researchdesk/provision-roles.sql
