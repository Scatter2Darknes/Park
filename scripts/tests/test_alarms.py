"""Tests for scripts/alarms.py, the `dumpsys alarm` decoder. Runs against saved dumps in tests/fixtures/, no device.

Fixtures:
  dumpsys-alarm-emulator.txt    a REAL capture from the emulator (Android 17): three Park alarms (reminder -120, urgent
                                -15, roll-forward), each with the extra `type=... window=0 exactAllowReason=permission`
                                line, plus other apps' alarms that must be ignored.
  dumpsys-package-notifications-granted.txt / -denied.txt
                                the POST_NOTIFICATIONS part of `dumpsys package com.example.park`. The runtime line is REAL
                                (emulator, Android 17); the denied one changes only granted=true to granted=false, which is
                                the exact line the owner's S25 printed (flags USER_SET|USER_SENSITIVE_WHEN_GRANTED|...).
  dumpsys-alarm-s25-format.txt  the phone's format, RECONSTRUCTED from the appendix of docs/Automation_plan.md (real lines
                                from the owner's S25) plus a third alarm and a history block: no `type=` line per alarm,
                                so inexact/exact has to come from the history's WL= value.

Run:  python -m unittest discover -s scripts/tests -v
"""

import contextlib
import io
import json
import sys
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import alarms  # noqa: E402
from common import Adb, Result  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
EMULATOR = (FIXTURES / "dumpsys-alarm-emulator.txt").read_text(encoding="utf-8")
S25 = (FIXTURES / "dumpsys-alarm-s25-format.txt").read_text(encoding="utf-8")
PACKAGE_GRANTED = (FIXTURES / "dumpsys-package-notifications-granted.txt").read_text(encoding="utf-8")
PACKAGE_DENIED = (FIXTURES / "dumpsys-package-notifications-denied.txt").read_text(encoding="utf-8")
S25_DENIED_LINE = ("android.permission.POST_NOTIFICATIONS: granted=false, "
                   "flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]")


class EmulatorFormatTest(unittest.TestCase):
    def setUp(self):
        self.result = alarms.parse_dumpsys_alarm(EMULATOR)

    def test_finds_exactly_the_three_park_alarms_and_ignores_other_apps(self):
        self.assertEqual(3, len(self.result.alarms))
        self.assertEqual([46, 47, 48], [a.number for a in self.result.alarms])

    def test_kinds_by_receiver(self):
        self.assertEqual(["reminder", "reminder", "roll-forward"], [a.kind for a in self.result.alarms])
        self.assertEqual(["ParkingReminderReceiver", "ParkingReminderReceiver", "ScheduleRollForwardReceiver"],
                         [a.receiver for a in self.result.alarms])

    def test_due_times_are_the_real_epoch_millis(self):
        self.assertEqual([1790856000000, 1790862300000, 1790863200000], [a.when_ms for a in self.result.alarms])
        self.assertEqual(["Thu 2026-10-01 05:00", "Thu 2026-10-01 06:45", "Thu 2026-10-01 07:00"],
                         [alarms.format_time(a.when_ms, alarms.SF_TZ_NAME) for a in self.result.alarms])

    def test_minutes_before_the_sweep_are_derived_from_the_roll_forward_alarm(self):
        self.assertEqual([-120, -15, None], [a.minutes_before_roll for a in self.result.alarms])

    def test_all_three_are_exact_from_the_live_window_line(self):
        for a in self.result.alarms:
            self.assertFalse(a.inexact)
            self.assertEqual("live window", a.inexact_source)
            self.assertEqual("permission", a.exact_reason)

    def test_pending_intent_ids_are_captured(self):
        self.assertEqual(["5cbf04f", "b4213ba", "50ab261"], [a.pi_id for a in self.result.alarms])

    def test_an_inexact_window_written_as_a_duration_is_recognised(self):
        """The dump prints an inexact window like '+1h0m0s0ms', not a number (seen on another app's alarm)."""
        text = EMULATOR.replace("window=0 exactAllowReason=permission", "window=+1h0m0s0ms exactAllowReason=not_applicable", 1)
        first = alarms.parse_dumpsys_alarm(text).alarms[0]
        self.assertTrue(first.inexact)
        self.assertEqual(3_600_000, first.window_ms)

    def test_minutes_are_flagged_approximate_when_there_are_several_roll_forward_alarms(self):
        """A sweep AND an RPP roll-forward alarm can coexist; the dump can't say which a reminder belongs to."""
        second_roll = EMULATOR.replace("1790863200000", "1790900000000").replace("50ab261", "beef123")             .replace("origWhen=2026-10-01 07:00:00.000", "origWhen=2026-10-01 17:13:20.000")
        # Give the second copy a different alarm number so it is a distinct entry, and append it.
        second_roll = second_roll.replace("#48:", "#58:")
        result = alarms.parse_dumpsys_alarm(EMULATOR + "\n" + second_roll)
        rolls = [a for a in result.alarms if a.kind == "roll-forward"]
        self.assertEqual(2, len(rolls))
        reminders = [a for a in result.alarms if a.kind == "reminder" and a.minutes_before_roll is not None]
        self.assertTrue(reminders and all(a.roll_ambiguous for a in reminders))
        table = alarms.render_table(result.alarms, alarms.SF_TZ_NAME)
        self.assertIn("min*", table)
        self.assertIn("More than one roll-forward alarm exists", table)

    def test_a_single_roll_forward_alarm_means_the_minutes_are_exact_and_carry_no_footnote(self):
        table = alarms.render_table(alarms.parse_dumpsys_alarm(EMULATOR).alarms, alarms.SF_TZ_NAME)
        self.assertNotIn("*", table)

    def test_summary_line(self):
        summary = alarms.summarize(self.result.alarms, alarms.Permissions(exact_alarm="allow"))
        self.assertEqual("3 live alarms; next: Thu 2026-10-01 05:00 PT; inexact: no; exact permission: allow", summary)


class S25FormatTest(unittest.TestCase):
    """The format from the owner's phone: alarms carry no window line, so inexact comes from the alarm history."""

    def setUp(self):
        self.result = alarms.parse_dumpsys_alarm(S25)
        self.by_pi = {a.pi_id: a for a in self.result.alarms}

    def test_reproduces_the_three_alarms_and_ignores_the_other_app(self):
        self.assertEqual(3, len(self.result.alarms))
        self.assertEqual(["reminder", "reminder", "roll-forward"], [a.kind for a in self.result.alarms])

    def test_times_match_the_appendix_1790082000000_is_6am_and_the_sweep_is_8am_pt(self):
        self.assertEqual(
            ["Tue 2026-09-22 06:00", "Tue 2026-09-22 07:45", "Tue 2026-09-22 08:00"],
            [alarms.format_time(a.when_ms, alarms.SF_TZ_NAME) for a in sorted(self.result.alarms, key=lambda a: a.when_ms)],
        )

    def test_minutes_before_the_sweep(self):
        reminders = sorted((a for a in self.result.alarms if a.kind == "reminder"), key=lambda a: a.when_ms)
        self.assertEqual([-120, -15], [a.minutes_before_roll for a in reminders])

    def test_inexact_is_read_from_the_most_recent_history_entry_for_the_same_pending_intent(self):
        two_hour = self.by_pi["2b6896f"]
        self.assertTrue(two_hour.inexact, "WL=3600000 is the latest entry (rtc 18:11:18.936), so it is inexact")
        self.assertEqual("history WL", two_hour.inexact_source)

    def test_the_latest_entry_is_chosen_by_rtc_time_not_by_position_in_the_file(self):
        # In the fixture an OLDER WL=0 entry (rtc 17:00) is listed AFTER the newer WL=3600000 one.
        latest = alarms._latest_history_by_pi(self.result.history)["2b6896f"]
        self.assertEqual(3_600_000, latest.window_ms)
        self.assertEqual("2026-09-20 18:11:18.936", latest.rtc)

    def test_alarms_whose_history_says_window_zero_are_exact(self):
        self.assertFalse(self.by_pi["9d2e4f7"].inexact)
        self.assertFalse(self.by_pi["5a53b05"].inexact)

    def test_an_alarm_with_no_history_entry_is_reported_as_window_unknown(self):
        without_history = S25.split("Alarm history")[0]
        for a in alarms.parse_dumpsys_alarm(without_history).alarms:
            self.assertIsNone(a.inexact)
            self.assertEqual("unknown", a.inexact_source)
        self.assertIn("window unknown", alarms.render_table(alarms.parse_dumpsys_alarm(without_history).alarms, alarms.SF_TZ_NAME))

    def test_summary_says_inexact_yes(self):
        self.assertEqual(
            "3 live alarms; next: Tue 2026-09-22 06:00 PT; inexact: yes; exact permission: deny",
            alarms.summarize(self.result.alarms, alarms.Permissions(exact_alarm="deny")),
        )


class ToleranceTest(unittest.TestCase):
    def test_a_line_it_cannot_parse_is_reported_raw_and_does_not_break_the_rest(self):
        odd = "    RTC_WAKEUP #9: Alarm{abc newformat com.example.park}\n" + EMULATOR
        result = alarms.parse_dumpsys_alarm(odd)
        self.assertEqual(3, len(result.alarms))
        self.assertEqual(1, len(result.unparsed))
        self.assertIn("newformat", result.unparsed[0])

    def test_empty_or_garbage_input(self):
        for text in ("", "nothing here\nat all\n"):
            result = alarms.parse_dumpsys_alarm(text)
            self.assertEqual([], result.alarms)
        self.assertEqual("0 live alarms; exact permission: unknown", alarms.summarize([], alarms.Permissions()))

    def test_an_alarm_listed_twice_is_counted_once(self):
        self.assertEqual(3, len(alarms.parse_dumpsys_alarm(EMULATOR + "\n" + EMULATOR).alarms))

    def test_a_block_missing_its_tag_line_still_parses_with_an_unknown_receiver(self):
        text = "    RTC_WAKEUP #5: Alarm{aa type 0 origWhen 1790856000000 whenElapsed 1 com.example.park}\n" \
               "      operation=PendingIntent{x: PendingIntentRecord{abc123 com.example.park broadcastIntent}}\n"
        (only,) = alarms.parse_dumpsys_alarm(text).alarms
        self.assertEqual("unknown", only.receiver)
        self.assertEqual("abc123", only.pi_id)


class WindowAndTimeTest(unittest.TestCase):
    def test_parse_window(self):
        self.assertEqual(0, alarms.parse_window("0"))
        self.assertEqual(3_600_000, alarms.parse_window("3600000"))
        self.assertEqual(3_600_000, alarms.parse_window("+1h0m0s0ms"))
        self.assertEqual(90_500, alarms.parse_window("+1m30s500ms"))
        self.assertIsNone(alarms.parse_window("banana"))

    def test_daylight_saving_is_handled_in_both_zones(self):
        summer = 1790856000000   # 2026-10-01 12:00 UTC -> 05:00 PDT
        winter = 1798804800000   # 2027-01-01 12:00 UTC -> 04:00 PST
        self.assertEqual("Thu 2026-10-01 05:00", alarms.format_time(summer, alarms.SF_TZ_NAME))
        self.assertEqual("Fri 2027-01-01 04:00", alarms.format_time(winter, alarms.SF_TZ_NAME))
        self.assertEqual("Thu 2026-10-01 08:00", alarms.format_time(summer, "America/New_York"))

    def test_the_fallback_pacific_rules_agree_with_the_tz_database(self):
        import datetime as dt
        for ms in (1790856000000, 1798804800000, 1773000000000, 1783000000000):
            utc = dt.datetime.fromtimestamp(ms / 1000, dt.timezone.utc)
            expected = alarms.format_time(ms, alarms.SF_TZ_NAME)
            self.assertEqual(expected, alarms._pacific_fallback(utc).strftime("%a %Y-%m-%d %H:%M"), ms)


class AppOpsTest(unittest.TestCase):
    def test_parses_common_appops_outputs(self):
        self.assertEqual("allow", alarms.parse_appop("Uid mode: SCHEDULE_EXACT_ALARM: allow", "SCHEDULE_EXACT_ALARM"))
        self.assertEqual("deny", alarms.parse_appop("SCHEDULE_EXACT_ALARM: deny; time=+3h2m ago", "SCHEDULE_EXACT_ALARM"))
        self.assertEqual("ignore", alarms.parse_appop("Uid mode: POST_NOTIFICATION: ignore", "POST_NOTIFICATION"))
        self.assertEqual("unknown", alarms.parse_appop("No operations.", "POST_NOTIFICATION"))


class OutputTest(unittest.TestCase):
    def test_json_has_the_same_facts_as_the_table(self):
        result = alarms.parse_dumpsys_alarm(EMULATOR)
        data = alarms.to_json(result, alarms.Permissions("allow", "allow"), alarms.SF_TZ_NAME)
        json.dumps(data)  # serialisable
        self.assertEqual(3, len(data["alarms"]))
        self.assertEqual([-120, -15, None], [a["minutes_before_sweep"] for a in data["alarms"]])
        self.assertEqual({"exact_alarm": "allow", "notifications": "allow", "notifications_blocked": False}, data["permissions"])

    def test_table_labels_the_timing(self):
        table = alarms.render_table(alarms.parse_dumpsys_alarm(S25).alarms, alarms.SF_TZ_NAME)
        self.assertIn("INEXACT", table)
        self.assertIn("-120 min", table)
        self.assertIn("sweep start", table)


class NotificationsPermissionTest(unittest.TestCase):
    """Reminders are notifications: with them blocked, alarms fire and nothing is shown. The runtime grant in
    `dumpsys package` is the primary source on Android 13+ (appops just says "ignore")."""

    def test_reads_the_grant_from_a_real_package_dump(self):
        self.assertIs(True, alarms.parse_notification_grant(PACKAGE_GRANTED))
        self.assertIs(False, alarms.parse_notification_grant(PACKAGE_DENIED))

    def test_reads_the_exact_line_from_the_owners_s25(self):
        self.assertIs(False, alarms.parse_notification_grant("      runtime permissions:\n        " + S25_DENIED_LINE))
        self.assertIs(True, alarms.parse_notification_grant(S25_DENIED_LINE.replace("granted=false", "granted=true")))

    def test_the_requested_permissions_list_alone_is_not_an_answer(self):
        only_requested = "    requested permissions:\n      android.permission.POST_NOTIFICATIONS\n"
        self.assertIsNone(alarms.parse_notification_grant(only_requested))
        self.assertIsNone(alarms.parse_notification_grant(""))

    def test_android_13_plus_trusts_the_grant_over_appops(self):
        # appops printed "ignore" on the S25 - correct but cryptic; the grant says what it means.
        self.assertEqual(("deny", "dumpsys package"), alarms.resolve_notifications(36, False, "ignore"))
        self.assertEqual(("deny", "dumpsys package"), alarms.resolve_notifications(33, False, "allow"))
        self.assertEqual(("allow", "dumpsys package"), alarms.resolve_notifications(36, True, "unknown"))

    def test_older_android_and_missing_grant_fall_back_to_appops(self):
        self.assertEqual(("ignore", "appops"), alarms.resolve_notifications(30, None, "ignore"))
        self.assertEqual(("allow", "appops"), alarms.resolve_notifications(36, None, "allow"))
        # Below Android 13 the permission isn't a runtime one, so a grant line (if one ever appeared) is not trusted.
        self.assertEqual(("allow", "appops"), alarms.resolve_notifications(29, False, "allow"))

    def test_an_unknown_sdk_still_trusts_a_grant_line(self):
        self.assertEqual(("deny", "dumpsys package"), alarms.resolve_notifications(None, False, "unknown"))

    def test_nothing_known_is_unknown_not_blocked(self):
        self.assertEqual(("unknown", "unknown"), alarms.resolve_notifications(36, None, "unknown"))
        self.assertFalse(alarms.Permissions().notifications_blocked)

    def test_which_states_count_as_blocked(self):
        for state in ("deny", "ignore"):
            self.assertTrue(alarms.Permissions("allow", state).notifications_blocked, state)
        for state in ("allow", "default", "foreground", "unknown"):
            self.assertFalse(alarms.Permissions("allow", state).notifications_blocked, state)


class NotificationsReportTest(unittest.TestCase):
    """The whole flow through read_permissions and main(), with a fake device standing in for adb."""

    class FakeDevice(Adb):
        def __init__(self, package_dump, notif_appop, sdk="36"):
            super().__init__("adb", "emulator-5554")
            self.package_dump, self.notif_appop, self.sdk = package_dump, notif_appop, sdk

        def shell(self, command, allow_fail=False, timeout=120):
            if command.startswith("appops get") and "SCHEDULE_EXACT_ALARM" in command:
                return Result(0, "Uid mode: SCHEDULE_EXACT_ALARM: allow\n", "")
            if command.startswith("appops get") and "POST_NOTIFICATION" in command:
                return Result(0, self.notif_appop, "")
            if command.startswith("dumpsys package"):
                return Result(0, self.package_dump, "")
            if command.startswith("getprop ro.build.version.sdk"):
                return Result(0, self.sdk + "\n", "")
            if command.startswith("getprop"):
                return Result(0, "America/Los_Angeles\n", "")
            if command.startswith("dumpsys alarm"):
                return Result(0, EMULATOR, "")
            raise AssertionError(f"unexpected command: {command}")

    def run_main(self, device):
        out = io.StringIO()
        with unittest.mock.patch.object(alarms, "connect", return_value=device), contextlib.redirect_stdout(out):
            self.assertEqual(0, alarms.main([]))
        return out.getvalue()

    def test_blocked_prints_the_loud_warning_and_names_the_source(self):
        text = self.run_main(self.FakeDevice(PACKAGE_DENIED, "Uid mode: POST_NOTIFICATION: ignore\n"))
        self.assertIn("notifications permission: deny (from dumpsys package)", text)
        self.assertIn("WARNING: notifications are BLOCKED", text)
        self.assertIn("reminders will not be shown", text)

    def test_allowed_prints_no_warning(self):
        text = self.run_main(self.FakeDevice(PACKAGE_GRANTED, "No operations.\n"))
        self.assertIn("notifications permission: allow (from dumpsys package)", text)
        self.assertNotIn("WARNING", text)

    def test_old_android_uses_appops_and_still_warns_when_ignored(self):
        text = self.run_main(self.FakeDevice("", "Uid mode: POST_NOTIFICATION: ignore\n", sdk="30"))
        self.assertIn("notifications permission: ignore (from appops)", text)
        self.assertIn("WARNING: notifications are BLOCKED", text)

    def test_json_carries_the_blocked_flag(self):
        permissions = alarms.read_permissions(self.FakeDevice(PACKAGE_DENIED, "Uid mode: POST_NOTIFICATION: ignore\n"))
        data = alarms.to_json(alarms.parse_dumpsys_alarm(EMULATOR), permissions, alarms.SF_TZ_NAME)
        self.assertEqual("deny", data["permissions"]["notifications"])
        self.assertTrue(data["permissions"]["notifications_blocked"])


if __name__ == "__main__":
    unittest.main()
