"""Exercise a local demo server; tokens remain in memory and are never printed."""
import argparse
from datetime import datetime, timedelta
import json
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8087)
    parser.add_argument("--expect-cache", choices=["REDIS", "DB_FALLBACK", "DISABLED"], required=True)
    parser.add_argument("--expect-mq", choices=["RABBITMQ", "LOCAL_FALLBACK", "DISABLED"], required=True)
    args = parser.parse_args()
    base = f"http://127.0.0.1:{args.port}/api"

    def call(path, method="GET", body=None, token=None, expected=200):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        request = urllib.request.Request(base + path, None if body is None else json.dumps(body).encode(), headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=12)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.load(response)
            assert response.code == expected, f"{method} {path}: expected {expected}, got {response.code}"
            return result.get("data", result)

    call("/health")
    accounts = call("/auth/options")["demoUsers"]
    assert len(accounts) >= 4, "A local demo database is required"
    admin_user = next(user for user in accounts if user["admin"])
    regular = [user for user in accounts if not user["admin"]]
    def login(user):
        return call("/auth/demo", "POST", {"userId": user["id"]})["token"]
    admin, manager, student, waiter = [login(user) for user in [admin_user, *regular[:3]]]
    tag = uuid.uuid4().hex[:8]
    club = call("/clubs", "POST", {"name": "联调社团 " + tag, "description": "本地接口验证", "color": "blue", "managerId": regular[0]["id"]}, admin)["id"]
    now = datetime.now().replace(microsecond=0)
    def timestamp(value):
        return value.isoformat()
    activity = call("/activities", "POST", {"clubId": club, "title": "联调活动 " + tag, "description": "验证报名、候补与器材预约", "category": "ART", "location": "东操场", "poster": "", "startTime": timestamp(now + timedelta(days=2)), "endTime": timestamp(now + timedelta(days=2, hours=2)), "signupDeadline": timestamp(now + timedelta(days=1)), "capacity": 1}, manager)["id"]
    call(f"/activities/{activity}/decision", "POST", {"approve": True, "note": "本地联调"}, admin)
    assert call(f"/activities/{activity}/registration", "POST", {}, student)["status"] == "REGISTERED"
    assert call(f"/activities/{activity}/registration", "POST", {}, waiter)["status"] == "WAITLISTED"
    call(f"/activities/{activity}/registration", "DELETE", token=student)
    assert call(f"/activities/{activity}", token=waiter)["myRegistration"]["status"] == "REGISTERED"
    equipment_body = {"name": "联调相机 " + tag, "category": "摄影", "description": "本地验证", "image": "", "totalQuantity": 5, "enabled": True}
    equipment = call("/equipment", "POST", equipment_body, admin)["id"]
    start, end = timestamp(now + timedelta(hours=1)), timestamp(now + timedelta(hours=2))
    def loan():
        return call("/loans", "POST", {"activityId": activity, "equipmentId": equipment, "quantity": 3, "plannedStart": start, "plannedEnd": end, "reason": "本地验证", "requestKey": uuid.uuid4().hex}, manager)["id"]
    first, second = loan(), loan()
    call(f"/loans/{first}/decision", "POST", {"approve": True, "note": ""}, admin)
    call(f"/loans/{second}/decision", "POST", {"approve": True, "note": ""}, admin, expected=409)
    call("/equipment", token=student)
    equipment_body["description"] = "验证修改后缓存刷新"
    call(f"/equipment/{equipment}", "PUT", equipment_body, admin)
    current = next(row for row in call("/equipment", token=student) if row["id"] == equipment)
    assert current["description"] == equipment_body["description"]
    call(f"/clubs/{club}/members", token=student, expected=403)
    answer = call("/assistant", "POST", {"question": "我的报名活动"}, waiter)
    assert any(row["id"] == activity for row in answer["items"])
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        notices = call("/notifications", token=waiter)
        status = call("/workbench/status", token=admin)
        own_notices = [notice for notice in notices if tag in notice["body"]]
        if len(own_notices) >= 2 and status["mq"] == args.expect_mq:
            break
        time.sleep(0.25)
    assert len(own_notices) >= 2, "Committed notifications were not delivered"
    assert status["cache"] == args.expect_cache, status["cache"]
    assert status["mq"] == args.expect_mq, status["mq"]
    call(f"/activities/{activity}/cancel", "POST", {}, manager)
    rows = call("/loans", token=admin)
    assert all(row["status"] == "CANCELLED" for row in rows if row["id"] in (first, second))
    equipment_body["enabled"] = False
    call(f"/equipment/{equipment}", "PUT", equipment_body, admin)
    print(f"HTTP smoke passed: port={args.port}, cache={status['cache']}, mq={status['mq']}, model={answer['mode']}")


if __name__ == "__main__":
    main()
