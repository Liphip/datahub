logging {
  level = "info"
}

loki.write "default" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}

// ── Docker container discovery ───────────────────────────────────────────────

discovery.docker "containers" {
  host             = "unix:///var/run/docker.sock"
  refresh_interval = "5s"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets

  // Avoid feeding the collector's own log-tailing messages back into Loki.
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    regex = "alloy"
    action = "drop"
  }

  rule {
    source_labels = ["__meta_docker_container_name"]
    regex         = "/(.*)"
    target_label  = "container"
  }

  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    target_label  = "compose_service"
  }

  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_project"]
    target_label  = "compose_project"
  }

  rule {
    source_labels = ["__meta_docker_container_log_stream"]
    target_label  = "stream"
  }

  // Tag DataTurntable module containers (dt-warm-* and dt-run-*)
  rule {
    source_labels = ["container"]
    regex         = "(dt-warm-.*|dt-run-.*|dt-ephemeral-.*)"
    target_label  = "dt_container_type"
    replacement   = "module"
  }
}

// ── Base log collection (all containers) ────────────────────────────────────

loki.source.docker "containers" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.containers.output
  forward_to = [loki.process.router.receiver]
}

// ── Log processing pipeline ──────────────────────────────────────────────────
// Routes backend structured logs into Loki with enriched labels

loki.process "router" {
  // ── Backend structured log enrichment ──────────────────────────────────
  // Extract pipeline/run/stage/status from DataTurntable structured log lines.
  // Log format: "PIPELINE_COMPLETE pipeline=<name> run=<uuid> status=SUCCESS ..."

  stage.match {
    selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"}"

    // Parse key=value pairs emitted by PipelineConsumer structured logs
    stage.regex {
      expression = "(?:event=)?(?P<event>PIPELINE_COMPLETE|MODULE_EXEC|MODULE_STDOUT|MODULE_STDERR|CONNECTOR_[A-Z_]+|connector_[a-z_]+) (?P<fields>.*)"
    }
    stage.logfmt {
      source = "fields"
      mapping = {
        component = "component",
        pipeline = "pipeline",
        run = "run",
        node = "node",
        stage = "stage",
        status = "status",
        connector_id = "connector_id",
        connector_type = "connector_type",
        direction = "direction",
        outcome = "outcome",
      }
    }

    stage.labels {
      values = {
        event          = "event",
        component      = "component",
        dt_pipeline    = "pipeline",
        dt_run         = "run",
        dt_node        = "node",
        dt_stage       = "stage",
        dt_status      = "status",
        connector_id   = "connector_id",
        connector_type = "connector_type",
        direction      = "direction",
        outcome        = "outcome",
      }
    }

    // Add a 'level' label based on log message prefix
    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"^.*ERROR|FAILED|STDERR\""
      stage.static_labels {
        values = { log_level = "error" }
      }
    }

    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"^.*WARN|WARNING\""
      stage.static_labels {
        values = { log_level = "warn" }
      }
    }

    // Tag pipeline completion events
    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"PIPELINE_COMPLETE\""
      stage.static_labels {
        values = { event_type = "pipeline_complete" }
      }
      stage.labels {
        values = {
          dt_pipeline = "pipeline",
          dt_run      = "run",
          dt_status   = "status",
        }
      }
    }

    // Tag module execution events
    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"MODULE_EXEC\""
      stage.static_labels {
        values = { event_type = "module_exec" }
      }
      stage.labels {
        values = {
          dt_pipeline = "pipeline",
          dt_run      = "run",
          dt_stage    = "stage",
          dt_status   = "status",
        }
      }
    }

    // Tag module stdout captures
    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"MODULE_STDOUT\""
      stage.static_labels {
        values = { event_type = "module_stdout" }
      }
    }

    // Tag module stderr captures
    stage.match {
      selector = "{compose_service=~\"backend|control-api|public-api|ingest-edge|pipeline-executor\"} |~ \"MODULE_STDERR\""
      stage.static_labels {
        values = { event_type = "module_stderr", log_level = "warn" }
      }
    }
  }

  // ── Module container log enrichment ────────────────────────────────────
  // dt-warm-* and similar short-lived Python containers
  stage.match {
    selector = "{dt_container_type=\"module\"}"
    stage.static_labels {
      values = { event_type = "module_container" }
    }
  }

  forward_to = [loki.write.default.receiver]
}
