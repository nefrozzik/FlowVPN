import 'package:hiddify/core/localization/translations.dart';

enum Region {
  ir,
  cn,
  ru,
  af,
  id,
  tr,
  br,
  other;

  String present(TranslationsEn t) => switch (this) {
    ir => t.pages.settings.routing.generalOptions.regions.ir,
    cn => t.pages.settings.routing.generalOptions.regions.cn,
    ru => t.pages.settings.routing.generalOptions.regions.ru,
    af => t.pages.settings.routing.generalOptions.regions.af,
    id => t.pages.settings.routing.generalOptions.regions.id,
    tr => t.pages.settings.routing.generalOptions.regions.tr,
    br => t.pages.settings.routing.generalOptions.regions.br,
    other => t.pages.settings.routing.generalOptions.regions.other,
  };
}
