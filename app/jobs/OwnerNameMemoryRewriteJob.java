package jobs;

import memory.MemoryOwnerNameRewrite;
import models.Agent;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.Tx;

import java.util.List;

/**
 * Names the owner in place of "the user" in each agent's memories once its USER.md carries the name. The
 * bootstrap writes that line during a conversation, so this looks again every half hour rather than once.
 */
@OnApplicationStart(async = true)
@Every("30mn")
@NoTransaction
public class OwnerNameMemoryRewriteJob extends Job<Void> {

    @Override
    public void doJob() {
        // Tests drive MemoryOwnerNameRewrite.run directly; the boot pass would race their fixtures.
        if (Play.runningInTestMode()) return;
        List<Agent> agents = Tx.run(AgentService::listAll);
        for (var agent : agents) MemoryOwnerNameRewrite.run(agent);
    }
}
