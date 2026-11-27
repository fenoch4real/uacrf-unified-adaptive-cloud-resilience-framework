-- UACRF Milestone 0 PostgreSQL Bootstrap
-- Creates isolated databases per logical domain boundary so each service owns its schema and role.

CREATE DATABASE uacrf_identity;
CREATE DATABASE uacrf_catalog;
CREATE DATABASE uacrf_ingestion;
CREATE DATABASE uacrf_evidence;
CREATE DATABASE uacrf_objectives;
CREATE DATABASE uacrf_topology;
CREATE DATABASE uacrf_assessment;
CREATE DATABASE uacrf_policy;
CREATE DATABASE uacrf_recovery;
CREATE DATABASE uacrf_knowledge;
CREATE DATABASE uacrf_advisory;
CREATE DATABASE uacrf_reporting;
