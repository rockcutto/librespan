package eu.siacs.conversations.medialib.activities;

import com.zomato.photofilters.imageprocessors.Filter;

public class NamedFilter extends Filter {

    private String name;

    public NamedFilter() {
    }

    public NamedFilter(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }


}
