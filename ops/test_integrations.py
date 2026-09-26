#!/usr/bin/env python3
"""Required integration tests on unique synthetic containers; never delete databases/volumes."""
import argparse
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parent.parent
PROJECTS = ["APIGateway", "FeedService", "NotificationService", "PostService", "outbox"]


def free_loopback_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def published_port(compose, env, service, target):
    address = subprocess.check_output([*compose, "port", service, str(target)], env=env, cwd=ROOT, text=True).strip()
    host, port = address.rsplit(":", 1)
    if host != "127.0.0.1" or not port.isdecimal():
        raise RuntimeError(f"Unexpected integration endpoint for {service}")
    return int(port)


def verify_reports():
    """Missing or skipped infrastructure checks cannot turn the integration job green."""
    total = 0
    for project in PROJECTS:
        reports = sorted((ROOT / project / "build/test-results/integrationTest").glob("TEST-*.xml"))
        count = 0
        if not reports:
            raise RuntimeError(f"No integration test reports for {project}")
        for report in reports:
            suite = ET.parse(report).getroot()
            if any(int(suite.attrib.get(key, 0)) for key in ("skipped", "failures", "errors")):
                raise RuntimeError(f"Integration tests failed or skipped in {project}")
            count += int(suite.attrib.get("tests", 0))
        if count == 0:
            raise RuntimeError(f"No integration tests executed for {project}")
        total += count
        print(f"{project}: {count} integration tests, zero skipped", flush=True)
    print(f"Required infrastructure integration tests passed: {total}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep-running", action="store_true", help="Keep this unique synthetic stack running for investigation")
    args = parser.parse_args()
    project = "unimeow-integration-" + uuid.uuid4().hex[:16]
    env = os.environ.copy()
    # Redis/PostgreSQL let Docker allocate host ports. Kafka advertises its chosen
    # external port; a competing bind causes an explicit startup failure, not reuse.
    env["INTEGRATION_KAFKA_PORT"] = str(free_loopback_port())
    compose = ["docker", "compose", "-p", project, "-f", str(ROOT / "ops/compose.integrations.yml")]
    exit_code = 1
    try:
        print(f"Starting isolated synthetic infrastructure: {project}", flush=True)
        subprocess.run([*compose, "up", "-d", "--wait", "--wait-timeout", "180"], env=env, cwd=ROOT, check=True)
        redis_port = published_port(compose, env, "redis", 6379)
        postgres_port = published_port(compose, env, "postgres", 5432)
        kafka_port = published_port(compose, env, "kafka", 29092)
        jdbc = f"jdbc:postgresql://127.0.0.1:{postgres_port}/integration_tests"
        kafka = f"127.0.0.1:{kafka_port}"
        env.update({
            "TEST_REDIS_PORT": str(redis_port),
            "INTEGRATION_REDIS_PORT": str(redis_port),
            "INTEGRATION_POSTGRES_URL": jdbc,
            "INTEGRATION_POSTGRES_USER": "outbox_test",
            "INTEGRATION_POSTGRES_PASSWORD": "synthetic-test-only",
            "INTEGRATION_KAFKA_BOOTSTRAP": kafka,
            "OUTBOX_IT_JDBC_URL": jdbc,
            "OUTBOX_IT_KAFKA": kafka,
        })
        wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
        command = [str(wrapper), *[f":{name}:integrationTest" for name in PROJECTS],
                   "--no-daemon", "--console=plain", "--max-workers=2", "--rerun-tasks"]
        subprocess.run(command, cwd=ROOT, env=env, check=True)
        verify_reports()
        exit_code = 0
    except subprocess.CalledProcessError as error:
        print(f"Integration command failed with exit code {error.returncode}", file=sys.stderr)
        exit_code = error.returncode or 1
    except (KeyboardInterrupt, RuntimeError, OSError, ET.ParseError) as error:
        print(f"Integration run did not complete: {type(error).__name__}: {error}", file=sys.stderr)
    finally:
        if args.keep_running:
            print(f"Synthetic containers left running: {project}", flush=True)
        else:
            # Stop only this generated project's containers. No down, rm, pruning,
            # database drops, or volume removal, including on a failing test run.
            stopped = subprocess.run([*compose, "stop", "--timeout", "20"], cwd=ROOT, env=env)
            if stopped.returncode and exit_code == 0:
                exit_code = stopped.returncode
            print(f"Stopped synthetic stack {project}; containers and data retained", flush=True)
    return exit_code


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
