"""
Creates the DualBoost bond server from the saved Oracle Resource Manager stack, retrying
until Oracle has free Ampere capacity. Run every few minutes by .github/workflows/oracle-retry.yml.

Prints nothing private: this repository's Actions logs are public. No OCIDs, IPs or Terraform
logs are printed; the IP is read in the Oracle console (Compute -> Instances -> DualBoost).

Exit codes: 0 = still waiting for capacity (the workflow starts the next run); 2 = server exists
(workflow disables itself); 1 = a problem that needs a person; 3 = secrets not set up yet.
"""
import os
import sys
import time

import oci

LIVE = {"PROVISIONING", "STARTING", "RUNNING", "STOPPING", "STOPPED"}
BUSY_JOB = {"ACCEPTED", "IN_PROGRESS", "CANCELING"}


def say(msg: str) -> None:
    print(time.strftime("%H:%M:%S UTC"), msg, flush=True)


def config() -> dict:
    need = ["OCI_USER", "OCI_TENANCY", "OCI_FINGERPRINT", "OCI_REGION", "OCI_PRIVATE_KEY"]
    missing = [k for k in need if not os.environ.get(k, "").strip()]
    if missing:
        say("Waiting for setup: secrets not added yet (" + ", ".join(missing) + ").")
        sys.exit(3)  # not "still waiting for capacity": don't chain another run
    cfg = {
        "user": os.environ["OCI_USER"].strip(),
        "tenancy": os.environ["OCI_TENANCY"].strip(),
        "fingerprint": os.environ["OCI_FINGERPRINT"].strip(),
        "region": os.environ["OCI_REGION"].strip(),
        "key_content": os.environ["OCI_PRIVATE_KEY"].strip() + "\n",
    }
    oci.config.validate_config(cfg)
    return cfg


def main() -> int:
    cfg = config()
    tenancy = cfg["tenancy"]
    compute = oci.core.ComputeClient(cfg)
    rm = oci.resource_manager.ResourceManagerClient(cfg)

    # 1. Already have a server? Then we are done for good.
    instances = oci.pagination.list_call_get_all_results(compute.list_instances, compartment_id=tenancy).data
    mine = [i for i in instances if "dualboost" in (i.display_name or "").lower() and i.lifecycle_state in LIVE]
    if mine:
        say(f"A DualBoost server already exists (state: {mine[0].lifecycle_state}). Nothing more to do.")
        return 2

    # 2. The saved stack.
    stacks = oci.pagination.list_call_get_all_results(rm.list_stacks, compartment_id=tenancy).data
    stacks = [s for s in stacks if s.lifecycle_state == "ACTIVE"]
    named = [s for s in stacks if "dualboost" in (s.display_name or "").lower()]
    stack = (named or (stacks if len(stacks) == 1 else []) or [None])[0]
    if stack is None:
        say("No stack named DualBoost found in the root compartment. Save the instance form as a stack named DualBoost.")
        return 1

    # 3. A job already running on it (e.g. a manual Apply)? Let it finish.
    jobs = rm.list_jobs(stack_id=stack.id, sort_by="TIMECREATED", sort_order="DESC", limit=5).data
    if any(j.lifecycle_state in BUSY_JOB for j in jobs):
        say("A job is already running on the stack; checking again next time.")
        return 0

    # 4. Apply (auto-approved) and wait for the result.
    details = oci.resource_manager.models.CreateJobDetails(
        stack_id=stack.id,
        display_name="dualboost-auto-retry",
        job_operation_details=oci.resource_manager.models.CreateApplyJobOperationDetails(
            execution_plan_strategy="AUTO_APPROVED",
        ),
    )
    job = rm.create_job(details).data
    say("Asked Oracle for a server (Apply started)...")
    deadline = time.time() + 15 * 60
    while job.lifecycle_state in BUSY_JOB and time.time() < deadline:
        time.sleep(15)
        job = rm.get_job(job.id).data
    if job.lifecycle_state == "SUCCEEDED":
        say("SUCCESS: the DualBoost server was created. Copy its IP in Oracle: Compute -> Instances -> DualBoost.")
        return 2
    if job.lifecycle_state in BUSY_JOB:
        say("The job is still running after 15 minutes; checking again next time.")
        return 0

    # 5. Failed: out of capacity is expected (try again later); anything else needs a look.
    try:
        logs = rm.get_job_logs_content(job.id).data or ""
    except oci.exceptions.ServiceError:
        logs = ""
    if "capacity" in logs.lower():
        say("No free Ampere server in your region right now. Trying again in a few minutes.")
        return 0
    say("The Apply failed for another reason. See Oracle: Resource Manager -> Stacks -> DualBoost -> Jobs (latest).")
    return 1


def once() -> int:
    try:
        return main()
    except oci.exceptions.ServiceError as e:
        if e.status == 429 or e.status >= 500:
            say(f"Oracle is busy (HTTP {e.status}); trying again shortly.")
            return 0
        # Status and code only: messages can contain OCIDs.
        say(f"Oracle refused the request (HTTP {e.status}, {e.code}). Check the API key secrets.")
        return 1


if __name__ == "__main__":
    # LOOP_MINUTES > 0: keep trying every EVERY_SECONDS within one run (GitHub runs timed jobs
    # only every few hours on small repositories, so the workflow keeps itself going instead).
    budget = float(os.environ.get("LOOP_MINUTES", "0")) * 60
    every = float(os.environ.get("EVERY_SECONDS", "180"))
    start = time.time()
    attempt = 0
    while True:
        attempt += 1
        t0 = time.time()
        code = once()
        if code != 0 or time.time() - start + every > budget:
            if budget:
                say(f"{attempt} attempts in this run.")
            sys.exit(code)
        time.sleep(max(5, every - (time.time() - t0)))
