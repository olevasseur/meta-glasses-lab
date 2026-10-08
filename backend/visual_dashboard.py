"""Visual archive extension using the dashboard's cookie and CSRF boundary."""

from urllib.parse import urlencode

from fastapi import HTTPException, Request
from fastapi.responses import Response

import dashboard
import visual_api


def visual_page(request):
    day = request.query_params.get("day", "")
    query = request.query_params.get("q", "").strip()
    page = int(request.query_params.get("page", "0"))
    return {**visual_api.memory.browse(day, query, page), "day": day, "q": query,
            "capture_status": visual_api.memory.capture_status(), "page": page,
            "previous": urlencode({"day": day, "q": query, "page": page - 1}),
            "next": urlencode({"day": day, "q": query, "page": page + 1})}


@dashboard.router.get("/visual-memory/thumbnails/{frame_id}")
def thumbnail(frame_id: str):
    data = visual_api.memory.thumbnail(frame_id)
    if data is None:
        raise HTTPException(404, "Capture not found")
    return Response(data, media_type="image/jpeg")


@dashboard.router.post("/visual-memory/{action}")
def mutate(request: Request, action: str):
    form = request.state.form
    memory = visual_api.memory
    try:
        if action == "pin":
            if form.get("pinned") not in {"0", "1"}:
                raise ValueError("Invalid pin")
            if not memory.pin(form.get("id", ""), form["pinned"] == "1"):
                raise HTTPException(404, "Capture not found")
        elif action == "delete":
            if form.get("confirm") != "delete":
                raise ValueError("Confirm deletion")
            if not memory.delete_one(form.get("id", "")):
                raise HTTPException(404, "Capture not found")
        elif action == "delete-range":
            if form.get("confirm") != "delete":
                raise ValueError("Confirm deletion")
            memory.delete_range(form.get("start", ""), form.get("end", ""))
        elif action == "retention":
            memory.set_retention(int(form.get("days", "")))
        else:
            raise HTTPException(404, "Unknown visual memory action")
    except ValueError:
        raise HTTPException(422, "Invalid visual memory form; check dates, retention and confirmation") from None
    # Preserve browse context without accepting an arbitrary redirect URL.
    return dashboard.redirect("/dashboard/visual-memory?" + urlencode({
        "day": form.get("day", ""), "q": form.get("q", ""),
    }))


dashboard.register_page("visual-memory", "Visual memory", "visual-memory.html", visual_page)
