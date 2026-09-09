import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:hiddify/core/localization/translations.dart';
import 'package:hiddify/features/route_rules/notifier/rules_notifier.dart';
import 'package:hiddify/hiddifycore/generated/v2/config/route_rule.pb.dart';
import 'package:hiddify/utils/utils.dart';
import 'package:hooks_riverpod/hooks_riverpod.dart';

class PredefinedRulesModal extends HookConsumerWidget {
  const PredefinedRulesModal({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final t = ref.watch(translationsProvider).requireValue;
    final theme = Theme.of(context);

    final list = _rule(t);

    final initialSize = PlatformUtils.isDesktop ? .60 : .35;
    return SafeArea(
      child: DraggableScrollableSheet(
        initialChildSize: initialSize,
        maxChildSize: 0.85,
        expand: false,
        builder: (context, scrollController) => ScrollConfiguration(
          behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
          child: Column(
            children: [
              Expanded(
                child: ListView.separated(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  separatorBuilder: (context, index) => const Divider(height: 1, indent: 16, endIndent: 16),
                  // shrinkWrap: true,
                  controller: scrollController,
                  itemBuilder: (context, index) {
                    final item = list[index];
                    final description = item.$1;
                    final rule = item.$2;
                    return ListTile(
                      onTap: () async {
                        assert(rule.hasName() && rule.hasOutbound());
                        await ref.read(rulesNotifierProvider.notifier).addRule(rule);
                        if (context.mounted) context.pop();
                      },
                      title: Text(
                        rule.name,
                        style: Theme.of(context).textTheme.titleMedium!.copyWith(color: theme.colorScheme.onSurface),
                      ),
                      subtitle: Text(
                        description,
                        style: Theme.of(
                          context,
                        ).textTheme.bodyMedium!.copyWith(color: theme.colorScheme.onSurfaceVariant),
                      ),
                    );
                  },
                  itemCount: list.length,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  List<(String description, Rule rule)> _rule(Translations t) => <(String description, Rule rule)>[
    (
      t.pages.settings.routing.predefinedRules.ads.description,
      Rule.new(
        enabled: true,
        name: t.pages.settings.routing.predefinedRules.ads.name,
        outbound: Outbound.direct,
        ruleSets: ["geosite-category-ads-all"],
      ),
    ),
    (
      t.pages.settings.routing.predefinedRules.bypassLan.description,
      Rule.new(
        enabled: true,
        name: t.pages.settings.routing.predefinedRules.bypassLan.name,
        outbound: Outbound.direct,
        ruleSets: ["geosite-private", "geoip-private"],
      ),
    ),
  ];
}
