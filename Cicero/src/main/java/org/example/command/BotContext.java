package org.example.command;

import org.example.DatabaseManager;
import org.example.service.AiContextService;
import org.example.service.MistralService;
import org.example.service.RiotService;
import java.util.concurrent.ExecutorService;

public record BotContext(
    DatabaseManager db,
    RiotService riotService,
    MistralService mistralService,
    AiContextService aiContextService,
    ExecutorService executor
) {}