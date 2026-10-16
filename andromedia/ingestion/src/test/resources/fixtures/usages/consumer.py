from provider import create_event, AnomalyEvent

def consume(event_id):
    event = create_event(event_id)
    return event.event_id
